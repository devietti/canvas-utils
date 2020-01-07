package canvas;

import canvas.apiobjects.Assignment;
import canvas.apiobjects.Course;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/** For a given assignment, upload the same grade for each student. Used when almost everyone gets the same score (like
 *  for demos), and we manually adjust grades after the fact.
 *  NB: Canvas has a "set default grade" option in the Gradebook that obviates this. */
public class UploadDemoGrades {

    private static String ASSIGNMENT_ID = null;

    private static void sanityCheck() throws IOException {
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

    public static void main(String[] args) throws IOException {

        Common.setup();
        ASSIGNMENT_ID = Common.pickAssignment("demo");
        sanityCheck();

        System.out.print("Points to give to each student: ");
        System.out.flush();
        Scanner scanner = new Scanner(System.in);
        String proceed = scanner.next();
        int points = Integer.valueOf(proceed);

        GenericUrl url = new GenericUrl(Common.BASE_URL + "users");
        url.put("enrollment_type[]", "student");
        List<User> students = Common.getAsList("users", User[].class);
        for (User s : students) {

            System.out.format("Uploading grade for %s [%d points]...", s.name, points);
            System.out.flush();

            // use grade-multiple-submissions endpoint instead of grading a single one
            // hat tip to https://community.canvaslms.com/thread/26502-grading-an-assignment-without-a-submission
            url = new GenericUrl(Common.CourseURL() + "assignments/" + ASSIGNMENT_ID + "/submissions/update_grades");
            //url.put("grade_data["+s.id+"][posted_grade]", points);
            GradeData gd = new GradeData();
            gd.grade_data.put(s.id, new PostedGrade(points));
            Common.postJSON(url, gd, null); // could perhaps use wrapperKey to scrap the GradeData class

            System.out.println("uploaded.");
        }
    }
}

class GradeData {
    Map<Integer,PostedGrade> grade_data = new HashMap<>();
}
class PostedGrade {
    int posted_grade;
    PostedGrade(int i) {
        posted_grade = i;
    }
}