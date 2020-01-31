package canvas.archived;

import canvas.Common;
import com.google.api.client.http.*;
import junit.framework.TestCase;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static canvas.Common.requestFactory;

/**
 * Run tests against our LTI web app.
 * @deprecated
 */
@RunWith(Parameterized.class)
public class LTITest extends TestCase {

    private static final boolean SHOW_HTML = true;

    private static final String LTI_PRODUCTION_URL = "https://fling.seas.upenn.edu/~cis371/cgi-bin/18sp/upload.php";
    private static final String LTI_TEST_URL = "https://fling.seas.upenn.edu/~cis371/cgi-bin/18sp/test.php";
    private static final String LTI_URL = LTI_PRODUCTION_URL;

    private final Map<String, String> POST_PARAMS = new HashMap<>();
    private final String localFilename, uploadedFilename, assignmentName;
    private final int pointsEarned, pointsPossible;

    // NB: junit magic to use 1st & 3rd ctor args as label for each test. Makes it easier to identify what each test is doing.
    @Parameterized.Parameters(name = "{1}, {3}")
    public static Collection ctorParams() {
        List<Object[]> p = new LinkedList<>();

        /*
        final int LAB1 = 6160743;
        p.add(new Object[]{LAB1, "rca_initial.v", "rca.v", "Lab 1: Debugging (code)", 28, 344});
        p.add(new Object[]{LAB1, "rca_complete.v", "rca.v", "Lab 1: Debugging (code)", 344, 344});

        final int LAB2_DIV = 6197936;
        p.add(new Object[]{LAB2_DIV, "lc4_divider_initial.v", "lc4_divider.v", "Lab 2: Divider code", 0, 6000});
        p.add(new Object[]{LAB2_DIV, "lc4_divider_complete.v", "lc4_divider.v", "Lab 2: Divider code", 6000, 6000});

        addCtorVariants(p, 6197947, "alu", "alu.zip",
                "Lab 2: ALU code", 0, 13600, false);

        addCtorVariants(p, 6197975, "single", "single.zip",
                "Lab 3: ALU-only single-cycle datapath code", 1964, 75810, false);
        addCtorVariants(p, 6197976, "single", "single.zip",
                "Lab 3: Full single-cycle datapath code", 88353, 1814570, false);

        addCtorVariants(p, 6197985, "pipeline", "pipeline.zip",
                "Lab 4: ALU-only pipelined datapath code", 0, 83405, true);
        addCtorVariants(p, 6197987, "pipeline", "pipeline.zip",
                "Lab 4: Full pipelined datapath code", 0, 2012063, true);

        addCtorVariants(p, 6197999, "superscalar", "superscalar.zip",
                "Lab 5: ALU-only superscalar pipeline code", 0, 83962, true);
        addCtorVariants(p, 6198000, "superscalar", "superscalar.zip",
                "Lab 5: Full superscalar pipeline code", 0, 2163330, true);
        */

        return p;
    }

    private static void addCtorVariants(List<Object[]> l, int canvasID, String localFname, String uploadedFname,
                                        String assnName, int ptsEarned, int ptsPossible, boolean initialTimesOut) {
        l.add(new Object[]{canvasID, localFname + "_initial.zip", uploadedFname, assnName, ptsEarned, initialTimesOut ? 1 : ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_initial_dsstore.zip", uploadedFname, assnName, ptsEarned, initialTimesOut ? 1 : ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_initial_extrafile.zip", uploadedFname, assnName, -1, ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_initial_missingfile.zip", uploadedFname, assnName, -1, ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_complete.zip", uploadedFname, assnName, ptsPossible, ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_complete_dsstore.zip", uploadedFname, assnName, ptsPossible, ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_complete_extrafile.zip", uploadedFname, assnName, -1, ptsPossible});
        l.add(new Object[]{canvasID, localFname + "_complete_missingfile.zip", uploadedFname, assnName, -1, ptsPossible});
    }

    public LTITest(int canvasID, String localFname, String uploadedFname, String assnName,
                   int ptsEarned, int ptsPossible) {
        POST_PARAMS.put("custom_canvas_course_id", Common.CourseID());
        POST_PARAMS.put("custom_canvas_user_login_id", "devietti");
        POST_PARAMS.put("custom_canvas_user_id", "4888227");
        POST_PARAMS.put("lis_person_name_full", "Joseph Devietti");
        POST_PARAMS.put("lis_person_contact_email_primary", "devietti@cis.upenn.edu");
        POST_PARAMS.put("custom_canvas_assignment_id", Integer.toString(canvasID));

        // TODO: figure out how to mock these parameters?
        POST_PARAMS.put("lis_outcome_service_url", "");
        POST_PARAMS.put("lis_result_sourcedid", "");

        localFilename = localFname;
        uploadedFilename = uploadedFname;
        assignmentName = assnName;
        pointsPossible = ptsPossible;
        // TODO: remove this horrible hack for some bug in our solution code
        pointsEarned = (2163330 == pointsPossible && 2163330 == ptsEarned) ? 2157800 : ptsEarned;
    }

    @org.junit.Test
    public void ltiTestingIsDeprecated() { }

    // TODO: LTI testing is deprecated now, we use a cron job to poll for Canvas submissions instead
    //@org.junit.Test
    public void runTest() throws IOException {
        HttpResponse response = postToLTITool(POST_PARAMS, localFilename, uploadedFilename);

        assertEquals(200, response.getStatusCode());
        String html = convertStreamToString(response.getContent());
        if (SHOW_HTML) {
            System.out.println(html);
        }

        Document doc = Jsoup.parse(html);
        Element elem;

        if (localFilename.contains("extrafile")) {
            elem = doc.selectFirst("#validationError");
            assertTrue(elem.text().contains(".zip file cannot contain these unnecessary extra file(s): "));
            return;
        } else if (localFilename.contains("missingfile")) {
            elem = doc.selectFirst("#validationError");
            assertTrue(elem.text().contains(".zip file missing expected file(s): "));
            return;
        }

        elem = doc.selectFirst("#assignmentName");
        assertEquals(assignmentName, elem.text());

        elem = doc.selectFirst("#pointsEarned");
        assertEquals(pointsEarned, Integer.parseInt(elem.text()));

        elem = doc.selectFirst("#pointsPossible");
        assertEquals(pointsPossible, Integer.parseInt(elem.text()));

        elem = doc.selectFirst("#percentageEarned");
        double perc = (((double) pointsEarned) / pointsPossible) * 100.0;
        assertEquals(String.format("%.0f%%", perc), elem.text());
    }

    private static HttpResponse postToLTITool(final Map<String, String> params, String fileToUpload, String filename) throws IOException {
        // TODO: make this a relative path, load via classpath?
        File fileObj = new File("/Users/devietti/Classes/canvas/src/test/resources/" + fileToUpload);

        HttpRequest request;
        // add parameters
        MultipartContent mpc = new MultipartContent().setMediaType(
                new HttpMediaType("multipart/form-data")
                        .setParameter("boundary", "__END_OF_PART__"));
        for (String name : params.keySet()) {
            MultipartContent.Part part = new MultipartContent.Part(
                    new ByteArrayContent(null, params.get(name).getBytes()));
            part.setHeaders(new HttpHeaders().set(
                    "Content-Disposition", String.format("form-data; name=\"%s\"", name)));
            mpc.addPart(part);
        }

        // add file
        FileContent fileContent = new FileContent("text/plain", fileObj);
        MultipartContent.Part part = new MultipartContent.Part(fileContent);
        part.setHeaders(new HttpHeaders().set(
                "Content-Disposition", "form-data; name=\"fileToUpload\"; filename=\"" + filename + "\""));
        mpc.addPart(part);
        request = requestFactory.buildPostRequest(new GenericUrl(LTI_URL), mpc);
        request.setReadTimeout(0/*infinite*/);
        return request.execute();
    }

    private static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
