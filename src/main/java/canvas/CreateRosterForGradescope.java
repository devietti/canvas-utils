package canvas;

import canvas.apiobjects.Profile;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Create a roster of students from Canvas that is suitable for importing into Gradescope. Student
 * ID is the Canvas Student ID, for ease of exporting GS grades back to Canvas.
 */
public class CreateRosterForGradescope {

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length != 1) {
            System.out.println("Usage: " + CreateRosterForGradescope.class.getName() + " <csv-grades-file>");
            return;
        }
        final String CSV_FILE = args[0];

        Common.setup();

        GenericUrl url;

        // populate user list
        System.out.println("Getting list of Canvas users...");

        url = new GenericUrl(Common.CourseURL() + "users");
        url.put("enrollment_type", "student");
        url.put("include", "email");
        List<User> users = Common.getAsList(url, User[].class);

        Writer w = new FileWriter(CSV_FILE);
        CSVPrinter csvPrinter = new CSVPrinter(w, CSVFormat.DEFAULT
                .withHeader("Full Name", "Email", Common.CANVAS_SID_COLUMN, Common.PENN_SID_COLUMN));

        for (User u : users) {
            // NB: have to get student's email via their Profile
            Profile prof = Common.getAs(new GenericUrl(Common.BASE_URL + "users/" + u.id + "/profile"),
                    Profile.class);

            assert null == u.email;
            csvPrinter.printRecord(u.name, prof.primary_email, u.id, u.sis_user_id);
            csvPrinter.flush();
        }
        csvPrinter.close();
    }

}
