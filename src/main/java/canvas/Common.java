package canvas;

import canvas.apiobjects.*;
import canvas.archived.TrackExtensionUsage;
import com.google.api.client.http.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Common things needed for Canvas tools
 */
public class Common {

    public static final String BASE_URL = "https://upenn.instructure.com/api/v1/";

    private static String MY_COURSE_ID = "1473828"; // CIS 501 Fall 2019
    private static final String SANDBOX_COURSE_ID = "1177025";
    private static String COURSE_URL = BASE_URL+"courses/"+ CourseID() +"/";

    private static final String SANDBOX_BASE_URL = BASE_URL+"courses/"+SANDBOX_COURSE_ID+"/";
    public static final HttpRequestFactory requestFactory = new NetHttpTransport().createRequestFactory(request -> request.setParser(new JsonObjectParser(new GsonFactory())));
    /**
     * API keys, read from canvas.properties. Use instructor token by default, though tests can (and do) change this.
     */
    public static String TOKEN = null;
    public static String INSTRUCTOR_TOKEN = null;
    public static String STUDENT2_TOKEN = null;
    public static String STUDENT3_TOKEN = null;
    public static String STUDENT4_TOKEN = null;
    public static String STUDENT5_TOKEN = null;

    static final String CANVAS_SID_COLUMN = "Canvas Student ID";
    static final String PENN_SID_COLUMN = "Penn Student ID";

    private static final Pattern nextLinkPattern = Pattern.compile("<([^><]*)>; rel=\"next\"");

    public static void setup() throws IOException {
        Properties prop = new Properties();
        try (final InputStream stream =
                     TrackExtensionUsage.class.getClassLoader().getResourceAsStream("canvas.properties")) {
            prop.load(stream);
            INSTRUCTOR_TOKEN = prop.getProperty("JoeKey");
            STUDENT2_TOKEN = prop.getProperty("TestStudent2Key");
            STUDENT3_TOKEN = prop.getProperty("TestStudent3Key");
            STUDENT4_TOKEN = prop.getProperty("TestStudent4Key");
            STUDENT5_TOKEN = prop.getProperty("TestStudent5Key");
            TOKEN = INSTRUCTOR_TOKEN;
        }
    }

    /** Switch all API requests to use the Sandbox site */
    public static void useSandboxSite() {
        MY_COURSE_ID = SANDBOX_COURSE_ID;
        COURSE_URL = SANDBOX_BASE_URL;
    }

    /**
     * Creates a GET request and sends it to Canvas, returning the response.
     * @param url the url to request
     * @return the HTTP response from Canvas
     */
    public static HttpResponse get(GenericUrl url) throws IOException {
        url.put("per_page", 300);
        HttpRequest request = requestFactory.buildGetRequest(url);
        request.getHeaders().setAuthorization(TOKEN);
        HttpResponse response = request.execute();
        //assert !response.getHeaders().get("link").toString().contains("next") :
        //        "Request returned only partial data: " + response.getHeaders().get("link").toString();
        assert null == response.getHeaders().get("link") :
                "Request returned only partial data: " + response.getHeaders().get("link").toString();
        return response;
    }

    /**
     * Creates a GET request with a prefix of BASE/courses/{course-id}, so the caller need only
     * supply the URL suffix. This is a simple wrapper of Common.get(GenericUrl url).
     */
    public static HttpResponse get(String urlSuffix) throws IOException {
        GenericUrl url = new GenericUrl(CourseURL() + urlSuffix);
        return get(url);
    }

    /**
     * Creates a GET request with a prefix of BASE/courses/{course-id}, so the caller need only
     * supply the URL suffix. The response is parsed into an instance of the specified Java class.
     * Note that for requests that return a list, use Common.getAsList() instead.
     * @param <T> a Class object for the type to parse the response into
     * @return an instance of type clazz
     */
    public static <T> T getAs(String urlSuffix, Class<T> clazz) throws IOException {
        HttpResponse response = get(urlSuffix);
        return response.parseAs(clazz);
    }

    public static <T> T getAs(GenericUrl url, Class<T> clazz) throws IOException {
        HttpResponse response = get(url);
        return response.parseAs(clazz);
    }

    /**
     * Creates a GET request with a prefix of BASE/courses/{course-id}, so the caller need only
     * supply the URL suffix. The response is parsed into a list of the specified Java class.
     * Note that for requests that return a single element, use Common.getAs() instead.
     * @param <S> the return type
     * @param <T> an array Class object for S[]
     * @return a List
     */
    public static <S, T> List<S> getAsList(String urlSuffix, Class<T> arrayClass) throws IOException {
        GenericUrl url = new GenericUrl(Common.CourseURL() + urlSuffix);
        return Common.getAsList(url, arrayClass);
    }

    // NB: per https://stackoverflow.com/questions/18581002/how-to-create-a-generic-array, could make this slightly
    // cleaner with just <T> and Class<T> clazz, and we create the array ourselves via Array.newInstance (and then
    // extract it's class...). But we still need to pass in the class parameter.
    public static <S, T> List<S> getAsList(final GenericUrl url, Class<T> arrayClass) throws IOException {
        ArrayList<S> list = new ArrayList<>();

        // make a copy of url so we don't screw up the caller's copy
        GenericUrl myurl = new GenericUrl(url.toURL());
        myurl.put("per_page", 100);

        while (true) {
            HttpRequest request = requestFactory.buildGetRequest(myurl);
            request.getHeaders().setAuthorization(TOKEN);
            HttpResponse response = request.execute();
            // DEBUG
            //System.out.format("%s returned %s%n", myurl.toString(), response.parseAsString());
            T elements = response.parseAs(arrayClass);
            list.addAll(Arrays.asList((S[]) elements));

            String linkHeader = response.getHeaders().getFirstHeaderStringValue("link");
            if (null == linkHeader || !linkHeader.contains("rel=\"next\"")) { // no more pages
                return list;

            } else { // retrieve next page
                // extract next page link from header
                Matcher m = nextLinkPattern.matcher(linkHeader);
                assert m.find() : linkHeader;
                //System.out.println(m.group(1));
                myurl = new GenericUrl(m.group(1));
            }
        }
    }

    /**
     * make a POST request, sending the specified object encoded as JSON
     * @param url the URL to POST to
     * @param j the object to be encoded as JSON (via GsonFactory)
     * @param wrapperKey create an outer object with "wrapperKey" mapping to the j object (null for no wrapper key)
     * @return the HttpResponse from the request
     */
    public static HttpResponse postJSON(final GenericUrl url, Object j, String wrapperKey) throws IOException {
        JsonHttpContent content = new JsonHttpContent(new GsonFactory(), j);
        if (null != wrapperKey) content.setWrapperKey(wrapperKey);
        HttpRequest request = requestFactory.buildPostRequest(url, content);
        request.getHeaders().setAuthorization(TOKEN);
        return request.execute();
    }

    public static HttpResponse putJSON(final GenericUrl url, Object j, String wrapperKey) throws IOException {
        JsonHttpContent content = new JsonHttpContent(new GsonFactory(), j);
        if (null != wrapperKey) content.setWrapperKey(wrapperKey);
        HttpRequest request = requestFactory.buildPutRequest(url, content);
        request.getHeaders().setAuthorization(TOKEN);
        return request.execute();
    }

    /**
     * Follows the "Uploading via POST" sequence from
     * https://canvas.instructure.com/doc/api/file.file_uploads.html#method.file_uploads.url
     * @param url the URL to upload the file to
     * @param f the file to upload
     * @return the Canvas File object corresponding to the uploaded file
     */
    public static CanvasFile uploadFile(GenericUrl url, File f) throws IOException, InterruptedException {
        HttpResponse response;
        HttpRequest request;
        Map<String, String> map = new HashMap<>();
        assert f.isFile() && f.canRead();

        // CANVAS DOCS STEP 1: initiate upload
        //System.err.println(url.toString());
        map.put("name", f.getName());
        map.put("size", String.valueOf(f.length()));
        if (f.getName().endsWith(".v")) {
            map.put("content_type", "text/plain");
        }
        //System.err.println(map);

        UrlEncodedContent content = new UrlEncodedContent(map);
        request = requestFactory.buildPostRequest(url, content);
        request.getHeaders().setAuthorization(TOKEN);
        response = request.execute();
        UploadHandlePOST handle = response.parseAs(UploadHandlePOST.class);
        //System.err.println(handle.upload_params);

        // CANVAS DOCS STEP 2: upload file to specified endpoint
        MultipartContent mpc = new MultipartContent().setMediaType(
                new HttpMediaType("multipart/form-data")
                        .setParameter("boundary", "__END_OF_PART__"));
        for (String name : handle.upload_params.keySet()) { // pass along required params
            MultipartContent.Part part = new MultipartContent.Part(
                    new ByteArrayContent(null, handle.upload_params.get(name).getBytes()));
            part.setHeaders(new HttpHeaders().set(
                    "Content-Disposition", String.format("form-data; name=\"%s\"", name)));
            mpc.addPart(part);
        }

        // add file content
        FileContent fileContent = new FileContent("text/plain", f);
        MultipartContent.Part part = new MultipartContent.Part(fileContent);
        // NB: when Canvas File Uploads used to go to AWS S3, the `filename` and `Content-Type` info didn't need to be
        // here in addition to earlier parts of the form. However, I noticed that curl includes them, and now, the
        // inscloudgate.net endpoint seems to insist on their presence. Without them, we get an opaque 500 response.
        String contDisp = String.format("form-data; name=\"file\"; filename=\"%s\" Content-Type: %s",
                f.getName(), handle.upload_params.getOrDefault("content_type","text/plain"));
        part.setHeaders(new HttpHeaders().set("Content-Disposition", contDisp));
        mpc.addPart(part);
        request = requestFactory.buildPostRequest(new GenericUrl(handle.upload_url), mpc);
        response = request.execute();

        /* How things used to work (< 3 Jan 2019)
        The Canvas docs say that we should either get a 3xx redirect or a 201 response, and that we need to
        do one more request to get the actual Canvas File. However, I've never seen this happen in practice. After
        step 2, we instead get a 200 response along with JSON for the Canvas File object.
        if (200 == response.getStatusCode()) {
            assert null == response.getHeaders().get("Location");
            CanvasFile cfile = response.parseAs(CanvasFile.class);
            assert f.getName().equals(cfile.filename) : cfile.filename;
            assert f.length() == cfile.size : cfile.size;
            assert "processed".equals(cfile.workflow_state) : cfile.workflow_state;
            // ugh, seems necessary to ensure caller can use the file id in other requests
            Thread.sleep(1000);
            return cfile;
        }

        As of 3 Jan 2018, file uploads behave more like the docs specify and we get a 201 response with a Location
        header. However, we get the Canvas File object directly; we don't need to do a GET on the Location as the docs
        say is required. However, to stay compliant I am doing the GET in case things change in the future.
        NB: a 3xx redirect is also a valid response to Step 2; I haven't seen it though.
        */
        if (201 == response.getStatusCode()) {
            assert null != response.getHeaders().getLocation();
            //CanvasFile cfile0 = response.parseAs(CanvasFile.class);
            //assert f.getName().equals(cfile0.filename) : cfile0.filename;
            //assert f.length() == cfile0.size : cfile0.size;

            // CANVAS DOCS STEP 3: confirm upload's success
            CanvasFile cfile1 = Common.getAs(new GenericUrl(response.getHeaders().getLocation()), CanvasFile.class);
            assert f.getName().equals(cfile1.filename) : cfile1.filename;
            assert f.length() == cfile1.size : cfile1.size;
            // in practice, I saw the `workflow_state` as `upload_pending` for both CanvasFile objects

            return cfile1;
        }

        String msg = String.format("file upload step 2, Canvas responded: %d %s", response.getStatusCode(), response.getStatusMessage());
        throw new IllegalStateException(msg);
    }

    /**
     * Print a list of all the assignments in this course, and allow the user to choose one.
     *
     * @return assignment ID chosen
     */
    public static String pickAssignment(String filter) throws IOException {
        HttpResponse response;
        Course course = getAs("", Course.class);

        List<Assignment> everything = getAsList("assignments/", Assignment[].class);

        List<Assignment> homeworks = new LinkedList<>();
        for (Assignment a : everything) {
            // for quizzes, use pickQuiz() instead
            if (!Arrays.asList(a.submission_types).contains("online_quiz") &&
                    a.name.contains(filter)) {
                homeworks.add(a);
            }
        }

        System.out.format("Assignments from %s. Select one to upload grades for:%n", course.name);
        int i = 1;
        for (Assignment a : homeworks) {
            System.out.format(" %d) %s%n", i, a.name);
            i++;
        }
        Scanner scanner = new Scanner(System.in);
        int choice = Integer.parseInt(scanner.next());
        Assignment chosen = homeworks.get(choice - 1);

        System.out.format("Working with %s, ok? (y/n) ", chosen.name);
        String proceed = scanner.next();
        if (!proceed.equals("y")) {
            System.out.println("Exiting...");
            System.exit(1);
        }
        return Integer.toString(chosen.id);
    }

    /**
     * Print a list of all the quizzes in this course, and allow the user to choose one.
     *
     * @return quiz ID chosen
     */
    static String pickQuiz(String filter) throws IOException {
        HttpResponse response;
        Course course = getAs("", Course.class);

        response = get("quizzes/");
        Quiz[] everything = response.parseAs(Quiz[].class);
        List<Quiz> quizzes = new LinkedList<>();
        for (Quiz q : everything) {
            if (q.title.contains(filter)) {
                quizzes.add(q);
            }
        }

        System.out.format("Quizzes from %s. Select one to use:%n", course.name);
        int i = 1;
        for (Quiz q : quizzes) {
            System.out.format(" %d) %s%n", i, q.title);
            i++;
        }
        Scanner scanner = new Scanner(System.in);
        int choice = Integer.parseInt(scanner.next());
        Quiz chosen = quizzes.get(choice - 1);

        System.out.format("Working with %s, ok? (y/n) ", chosen.title);
        String proceed = scanner.next();
        if (!proceed.equals("y")) {
            System.out.println("Exiting...");
            System.exit(1);
        }
        return Integer.toString(chosen.id);
    }

    /**
     * @param z the time to be formatted
     * @return a String representing i in Canvas' web display format, e.g., "Dec 30 at 1:02PM"
     */
    public static String canvasDateFormat(ZonedDateTime z) {
        return DateTimeFormatter.ofPattern("MMM d 'at' h:mma").withZone(ZoneId.of("America/New_York")).format(z);
    }

    /**
     * Parse a timestamp string from Canvas. These are of the form ISO_INSTANT in the UTC timezone
     * @return a ZonedDateTime object representing the given timestamp
     */
    public static ZonedDateTime parseCanvasDate(String ts) {
        return ZonedDateTime.ofInstant(Instant.parse(ts), ZoneId.of("UTC"));
    }

    /** Helper function to convert a Throwable's stack trace to a string */
    public static String t2s(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    /** Helper function to convert a Throwable's stack trace to a string */
    public static String t2s(Throwable t, String msg) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.println();
        pw.println(t.getLocalizedMessage());
        pw.println(msg);
        return sw.toString();
    }

    public static void check_call(ProcessBuilder pb) throws IOException, InterruptedException {
        Process proc = pb.start();
        int rc = proc.waitFor();
        if (0 != rc) {
            throw new IOException("process " + pb.command().toString() + " exited with code " + rc);
        }
    }

    public static ProcessOutput check_output(ProcessBuilder pb) throws IOException, InterruptedException {
        Process proc = pb.start();
        int rc = proc.waitFor();
        if (0 != rc) {
            throw new IOException("process " + pb.command().toString() + " exited with code " + rc);
        }
        return new ProcessOutput(
                IOUtils.toString(proc.getInputStream(), Charset.defaultCharset()),
                IOUtils.toString(proc.getErrorStream(), Charset.defaultCharset()));
    }

    /** @return the URL for API calls, of the form BASE/courses/{course-id}/ - note this *includes* a trailing slash */
    public static String CourseURL() {
        return COURSE_URL;
    }

    /** Returns the Canvas Course ID (as a String) */
    public static String CourseID() {
        return MY_COURSE_ID;
    }
}
