package canvas.archived;

import canvas.Common;
import canvas.apiobjects.Group;
import canvas.apiobjects.Submission;
import canvas.apiobjects.SubmissionComment;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;

import java.io.IOException;
import java.util.*;

public class GetSubmissionComments {

    private static final String HOMEWORK_ID = "5449031"; // CIS 501 HW5

    public static void main(String[] args) throws IOException {
        Common.setup();

        HttpResponse response;

        GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + HOMEWORK_ID + "/submissions");
        url.put("include", "submission_comments");
        response = Common.get(url);
        Submission[] subs = response.parseAs(Submission[].class);

        Map<Integer,SubmissionComment> comments = new HashMap<>();
        for (Submission s : subs) {
            //System.out.println(Arrays.toString(s.submission_comments));
            if (0 == s.submission_comments.length) continue;

            // get the latest comment from each submission
            SubmissionComment lastComment = s.submission_comments[s.submission_comments.length-1];
            comments.put(lastComment.author_id, lastComment);
        }

        // get a list of groups, and for each group find a comment (from each group member) to print out

        response = Common.get("groups");
        Group[] groups = response.parseAs(Group[].class);
        for (Group g : groups) {
            if (!g.name.toLowerCase().startsWith("homework group ")) {
                continue;
            }
            response = Common.get(new GenericUrl(Common.BASE_URL + "groups/" + g.id + "/users"));
            g.members = response.parseAs(User[].class);

            List<SubmissionComment> coms = new LinkedList<>();
            for (User m : g.members) {
                if (!comments.containsKey(m.id)) continue;
                // remove group submissions from comments map
                coms.add(comments.remove(m.id));
            }

            assert coms.size() <= 1 : g.name + ": " + coms.size();
            if (coms.size() == 0) {
                //System.out.format("*** group %s has ZERO comments %n", g.name);
                continue;
            }

            System.out.format("**** %s: %s %n%n%n", g.name, coms.get(0));
        }

        // print out remaining comments from individuals submitting a group assignment
        for (SubmissionComment sc : comments.values()) {
            System.out.format("**** %s: %s %n%n%n", sc.author_name, sc.comment);
        }

    }

}
