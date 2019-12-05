package canvas;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Upload grades for an assignment, using data from Gradescope. Used for uploading finalized exam
 * scores.
 */
public class UploadGradescopeGrades {

    private final static boolean DEBUG = false;
    private static String ASSIGNMENT_ID = null;

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length != 1) {
            System.out.println("Usage: " + UploadGradescopeGrades.class.getName() + " <csv-grades-file>");
            return;
        }
        final String CSV_FILE = args[0];

        Common.setup();
        ASSIGNMENT_ID = Common.pickAssignment("Exam");

        Reader in = new FileReader(CSV_FILE);
        Iterable<CSVRecord> records = CSVFormat.EXCEL.withFirstRecordAsHeader().parse(in);

        for (CSVRecord record : records) {
            String canvasStudentId = record.get(Common.CANVAS_SID_COLUMN);
            String studentName = record.get("Name");
            String ts = record.get("Total Score");
            if (ts.equals("")) {
                System.out.format("Skipping empty score for %s%n", studentName);
                continue;
            }
            double score = Double.parseDouble(ts);

            System.out.format("Uploading grade+comment for %s [%f]...", studentName, score);
            System.out.flush();
            uploadScore(canvasStudentId, score);
            System.out.println("uploaded.");

            //if (DEBUG) { return; }
        }
    }

    private static void uploadScore(final String studentId, final double score) throws IOException {
        HttpRequest request;
        GenericUrl url;

        url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/" + studentId);
        url.put("submission[posted_grade]", score);

        request = Common.requestFactory.buildPutRequest(url, null);
        request.getHeaders().setAuthorization(Common.TOKEN);
        request.execute();
    }

}
