package canvas;

import canvas.apiobjects.*;
import com.google.api.client.http.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

import static canvas.Common.TOKEN;
import static canvas.Common.requestFactory;

/**
 * Upload grades for an assignment. Each submission is scored and receives a file attachment showing
 * test results.
 */
class UploadAssignmentGrades {

    private static String ASSIGNMENT_ID = null;
    private final static boolean DEBUG = false;
    private final static int MAX_POLL_ATTEMPTS = 10;
    private final static String UPLOADED_FILE_PATH = "etc/501testoutput.txt";

    static void sanityCheck() throws IOException {
        HttpResponse response;
        response = Common.get("");
        Course course = response.parseAs(Course.class);

        response = Common.get("assignments/" + ASSIGNMENT_ID);
        Assignment assn = response.parseAs(Assignment.class);
        System.out.format("Uploading grades for '%s' in '%s'. OK (y/n)? ", assn.name, course.name);
        Scanner scanner = new Scanner(System.in);
        String proceed = scanner.next();
        if (!proceed.equals("y")) {
            System.out.println("Exiting...");
            System.exit(1);
        }
    }

    private static class AuxGrade {
        String studentName;
        String studentId;
        double score;
        String comments;
    }

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: " + UploadAssignmentGrades.class.getName() + " <csv-grades-file> <test-output-dir> [aux-grades-csv-file]");
            return;
        }
        final String CSV_FILE = args[0];
        final String TEST_OUTPUT_DIR = args[1];
        final String AUX_CSV_FILE = 3 == args.length ? args[2] : null;

        Common.setup();
        ASSIGNMENT_ID = Common.pickAssignment("Homework");

        Map<String, AuxGrade> auxScores = new HashMap<>();
        if (AUX_CSV_FILE != null) {
            Reader in = new FileReader(AUX_CSV_FILE);
            Iterable<CSVRecord> records = CSVFormat.EXCEL.withFirstRecordAsHeader().parse(in);
            for (CSVRecord record : records) {
                AuxGrade aux = new AuxGrade();
                aux.studentName = record.get("submitter");
                aux.studentId = record.get("student id");
                aux.score = Double.parseDouble(record.get("points"));
                aux.comments = record.get("comments");
                auxScores.put(aux.studentId, aux);
            }
        }

        Reader in = new FileReader(CSV_FILE);
        Iterable<CSVRecord> records = CSVFormat.EXCEL.withFirstRecordAsHeader().parse(in);

        for (CSVRecord record : records) {
            String studentId = record.get("student id");
            double score = Double.parseDouble(record.get("earned points"));

            String auxComment = "";

            // extract graph/short answer points and feedback from the CSV file
            if (record.isSet("graph points")) {
                double graphPoints = Double.parseDouble(record.get("graph points"));
                score += graphPoints;
            }
            if (record.isSet("graph comment")) {
                auxComment += record.get("graph comment"+"\n");
            }

            if (AUX_CSV_FILE != null) {
                assert auxScores.containsKey(studentId) : "aux scores missing for: " + studentId;
                AuxGrade aux = auxScores.get(studentId);
                double auxPoints = aux.score;
                score += auxPoints;

                auxComment = aux.comments;
                if (!auxComment.equals("")) {
                    auxComment += "\n";
                }
            }
            String studentName = record.get("submitter");

            File testOutput = new File(String.format(TEST_OUTPUT_DIR + "/%s-%s-testoutput.txt", studentName, studentId));
            if (!testOutput.exists()) {
                testOutput = new File(String.format(TEST_OUTPUT_DIR + "/%s-late-%s-testoutput.txt", studentName, studentId));
                if (!testOutput.exists()) {
                    throw new IllegalArgumentException("test output file " + testOutput + " not found!");
                }
            }

            System.out.format("Uploading grade+comment for %s [%f]...", studentName, score);
            System.out.flush();
            uploadCommentForSubmissionViaURL(studentId, score, testOutput, auxComment);
            System.out.println("uploaded.");

            //if (DEBUG) { return; }
        }
    }

    // put file on internet, have Canvas pull it from there.
    private static void uploadCommentForSubmissionViaURL(final String studentId, final double score,
                                                         final File testOutput, final String auxComments) throws IOException {
        HttpResponse response;
        HttpRequest request;
        GenericUrl url;
        Map<String, String> map = new HashMap<>();

        // Step 0: push file to my web site
        Process scp = Runtime.getRuntime().exec("scp " + testOutput.getAbsolutePath() + " eniac:public_html/"+UPLOADED_FILE_PATH);
        int retval = 0;
        try {
            retval = scp.waitFor();
            assert 0 == retval : retval;
        } catch (InterruptedException e) {
            e.printStackTrace();

        }

        // Step 1: get an upload handle from Canvas
        if (DEBUG) { System.out.println(); }

        url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/" + studentId + "/comments/files");
        if (DEBUG) { System.out.println(url.toString()); }
        map.put("name", testOutput.getName());
        map.put("size", String.valueOf(testOutput.length()));
        map.put("url","http://www.cis.upenn.edu/~devietti/"+UPLOADED_FILE_PATH);
        if (DEBUG) { System.out.println(map); }

        UrlEncodedContent content = new UrlEncodedContent(map);
        request = requestFactory.buildPostRequest(url, content);
        request.getHeaders().setAuthorization(TOKEN);
        response = request.execute();
        UploadHandleURL handle = response.parseAs(UploadHandleURL.class);

        // Step 2: poll the status URL until the upload completes

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            response = Common.get(new GenericUrl(handle.status_url));
            StatusResponse status = response.parseAs(StatusResponse.class);
            switch (status.upload_status) {
                case "ready":
                    i = MAX_POLL_ATTEMPTS; // exit loop
                    break;
                case "errored":
                    throw new IllegalArgumentException(status.upload_status + " " + status.message);
                case "pending":
                    break;
                default:
                    throw new IllegalArgumentException(status.upload_status);
            }

            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }

        // Step 3: add comment to submission

        url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/" + studentId);
        url.put("comment[text_comment]", auxComments + "See attached file for test results and output.");
        url.put("submission[posted_grade]", score);
        url.put("comment[group_comment]", true);
        url.put("comment[file_ids]", handle.id);

        request = Common.requestFactory.buildPutRequest(url, null);
        request.getHeaders().setAuthorization(Common.TOKEN);
        request.execute();
    }

    // problematic 3-part POST uploads
    private static void uploadCommentForSubmissionViaPOST(final String studentId, final double score,
                                                          final File testOutput, final String auxComments)
            throws IOException {

        // hat tip to http://stackoverflow.com/questions/23001661/post-multipart-form-with-google-http-java-client

        HttpResponse response;
        HttpRequest request;
        GenericUrl url;
        Map<String, String> map = new HashMap<>();

        // Step 1: get an upload handle from Canvas
        System.out.println();

        url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/" + studentId + "/comments/files");
        System.out.println(url.toString());
        map.put("name", testOutput.getName());
        map.put("size", String.valueOf(testOutput.length()));
        System.out.println(map);

        UrlEncodedContent content = new UrlEncodedContent(map);
        request = requestFactory.buildPostRequest(url, content);
        request.getHeaders().setAuthorization(TOKEN);
        response = request.execute();
        //System.out.println("RESPONSE: "+response.parseAsString());
        UploadHandlePOST handle = response.parseAs(UploadHandlePOST.class);
        System.out.println(handle.upload_params);

        // Step 2: upload file contents

        // add parameters
        MultipartContent mpc = new MultipartContent().setMediaType(
                new HttpMediaType("multipart/form-data")
                        .setParameter("boundary", "__END_OF_PART__"));
        for (String name : handle.upload_params.keySet()) {
            MultipartContent.Part part = new MultipartContent.Part(
                    new ByteArrayContent(null, handle.upload_params.get(name).getBytes()));
            part.setHeaders(new HttpHeaders().set(
                    "Content-Disposition", String.format("form-data; name=\"%s\"", name)));
            mpc.addPart(part);
        }

        // add file
        FileContent fileContent = new FileContent("text/plain", testOutput);
        MultipartContent.Part part = new MultipartContent.Part(fileContent);
        part.setHeaders(new HttpHeaders().set(
                "Content-Disposition", "form-data; name=\"file\";"));
        mpc.addPart(part);
        request = requestFactory.buildPostRequest(new GenericUrl(handle.upload_url), mpc);
        response = request.execute();

        assert 200 == response.getStatusCode();
        assert null == response.getHeaders().get("Location");
        CanvasFile confirm = response.parseAs(CanvasFile.class);
        assert confirm.filename.equals(testOutput.getName());
        assert confirm.size == testOutput.length();

        /*
         NB: The Canvas API docs (https://canvas.instructure.com/doc/api/file.file_uploads.html) say
         that we have to make a 3rd request to Canvas to confirm that we want the file uploaded, but
         this does not seem to be necessary. The second request returns 200 (not 301 as the Canvas
         docs say) and we get a file id in the response that we can use in the comment submission
         and everything seems to work ok in my testing so far. The response from request 2 is what
         the docs specify as the response to request 3.
         */

        url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/" + studentId);
//        url.put("comment[text_comment]", auxComments + "See attached file for test results and output.");
        url.put("submission[posted_grade]", score);
        url.put("comment[group_comment]", true);
//        url.put("comment[file_ids]", confirm.id);

        request = Common.requestFactory.buildPutRequest(url, null);
        request.getHeaders().setAuthorization(Common.TOKEN);
        request.execute();
    }

}
