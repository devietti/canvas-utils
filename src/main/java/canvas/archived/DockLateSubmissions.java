package canvas.archived;

import canvas.Common;
import canvas.apiobjects.*;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * For each late submission, ask user whether to apply a deduction to the current grade. If so,
 * reduce grade and leave a comment mentioning the original grade.
 */
public class DockLateSubmissions {

    /**
     * Submissions a few minutes after the deadline don't incur the late penalty
     */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(30);
    /**
     * Penalty for a late submission: it gets 75% of its original score.
     */
    private static final double LATE_PENALTY = 0.75;
    /**
     * Structured comment to identify late submissions we've already processed, to avoid multiple
     * late penalties for a single submission.
     */
    private static final String LATE_SUB_TAG = "[Late Submission] ";
    private static final String UNACCEPTED_SUB_TAG = "[Submission Not Accepted] ";


    private static final Map<Integer, User> lookupUser = new HashMap<>();
    private static final Map<Integer, ZonedDateTime> lookupDueDate = new HashMap<>();
    private static final Map<Integer, ZonedDateTime> lookupLockDate = new HashMap<>();

    public static void main(String[] args) throws IOException {

        GenericUrl url;
        Scanner scanner = new Scanner(System.in);

        // read command-line args
        if (args.length != 0) {
            System.out.println("Usage: " + DockLateSubmissions.class.getName() + " ");
            return;
        }

        Common.setup();
        final String hwId = Common.pickAssignment("Lab");

        final Assignment hw = Common.getAs("assignments/" + hwId, Assignment.class);

        // determine default hw due and lock dates
        try {
            hw.due_at = Common.parseCanvasDate(hw.due_at_string);
            hw.lock_at = Common.parseCanvasDate(hw.lock_at_string);
            System.out.format("%s due at (%s) lock at (%s) %n", hw.name, hw.due_at_string, hw.lock_at_string);
        } catch (DateTimeParseException e) {
            System.err.format("*** Invalid due (%s) or lock (%s) date for hw %s%n",
                    hw.due_at_string, hw.lock_at_string, hw.name);
            System.exit(1);
        }

        // find overrides that change the due/lock dates for specific students
        List<AssignmentOverride> overrides = Common.getAsList("assignments/" + hwId + "/overrides",
                AssignmentOverride[].class);
        for (AssignmentOverride ao : overrides) {
            assert ao.assignment_id == hw.id;
            try {
                ao.due_at = Common.parseCanvasDate(ao.due_at_string);
                ao.lock_at = Common.parseCanvasDate(ao.lock_at_string);
                System.out.format(" Override: due at (%s) lock at (%s) for %s %n",
                        ao.due_at_string, ao.lock_at_string, Arrays.toString(ao.student_ids));
            } catch (DateTimeParseException e) {
                System.err.format("*** Invalid due (%s) or lock (%s) date for hw %s%n",
                        ao.due_at_string, ao.lock_at_string, hw.name);
                System.exit(1);
            }
            for (int sid : ao.student_ids) {
                lookupDueDate.put(sid, ao.due_at);
                lookupLockDate.put(sid, ao.lock_at);
            }
        }

        // populate user list
        System.out.println("Getting list of users...");
        url = new GenericUrl(Common.CourseURL() + "users");
        url.put("enrollment_type", "student");
        List<User> users = Common.getAsList(url, User[].class);

        for (User u : users) {
            lookupUser.put(u.id, u);
        }

        // TODO: determine user=>group mapping so that we don't process a single group submission redundantly for each group member

        // iterate over all submissions, identifying the late ones

        url = new GenericUrl(Common.CourseURL() + "assignments/" + hwId + "/submissions");
        url.put("include", "submission_comments");
        List<Submission> subs = Common.getAsList(url, Submission[].class);

        // NB: graded_at seems to be the only valid submission timestamp for LTI assignments
        // TODO: demos are non-LTI assignments where graded_at is the submission time :-/ Maybe just dock late demos via Gradebook?
        final boolean LTI_HW = Arrays.asList(hw.submission_types).contains("external_tool");

        for (Submission sub : subs) {
            try {
                sub.parseTimes();
                /*
                if (LTI_HW) {
                    sub.graded_at = Common.parseCanvasDate(sub.graded_at_string);
                } else {
                    sub.submitted_at = Common.parseCanvasDate(sub.submitted_at_string);
                }
                */
            } catch (DateTimeParseException e) {
                // ignore students who didn't submit anything
                boolean noSubmission = false;
                if (LTI_HW) {
                    noSubmission = sub.graded_at_string.equals("");
                } else {
                    noSubmission = sub.submitted_at_string.equals("");
                }

                if (!(noSubmission && 0 == Double.compare(sub.score, 0.0))) {
                    System.out.format("*** Invalid submitted_at (%s) or graded_at (%s) time from %s for %s (LTI=%b). They scored a %f.%n",
                            sub.submitted_at_string, sub.graded_at_string,
                            lookupUser.get(sub.user_id).name, hw.name, LTI_HW, sub.score);
                }
                continue;
            }

            final ZonedDateTime submittedAt = LTI_HW ? sub.graded_at : sub.submitted_at;
            assert null != submittedAt;
            //System.out.format("submitted at: %s, graded_at %s %n", sub.submitted_at_string, sub.graded_at_string);

            ZonedDateTime dueAt = hw.due_at;
            if (lookupDueDate.containsKey(sub.user_id)) {
                dueAt = lookupDueDate.get(sub.user_id);
            }
            ZonedDateTime lockAt = hw.lock_at;
            if (lookupLockDate.containsKey(sub.user_id)) {
                lockAt = lookupLockDate.get(sub.user_id);
            }

            if (submittedAt.isBefore(dueAt.plus(GRACE_PERIOD))) { // on-time submission
                continue;
            }
            System.out.format("Late submission from %s%n", lookupUser.get(sub.user_id).name);

            // check for comment showing we already processed this submission
            boolean skip = false;
            for (SubmissionComment sc : sub.submission_comments) {
                if (sc.comment.startsWith(LATE_SUB_TAG) || sc.comment.startsWith(UNACCEPTED_SUB_TAG)) {
                    System.out.format("Already processed late submission from %s %n", lookupUser.get(sub.user_id).name);
                    skip = true;
                    break;
                }
            }
            if (skip) continue;

            // calculate duration by which hw was late
            Duration lateness = Duration.between(dueAt, submittedAt);
            String latenessString = "";
            if (lateness.toDays() > 0) {
                latenessString += lateness.toDays() + "d ";
                lateness = lateness.minusDays(lateness.toDays());
            }
            if (lateness.toHours() > 0) {
                latenessString += lateness.toHours() + "h ";
                lateness = lateness.minusHours(lateness.toHours());
            }
            if (lateness.toMinutes() > 0) {
                latenessString += lateness.toMinutes() + "m ";
            }

            // score and text for submission comment
            assert sub.score != null;
            double origScore = sub.score;
            double newScore = origScore;
            String commentText = "";

            // ask what to do about this late submission
            if (submittedAt.isBefore(lockAt)) { // student(s) used extension
                System.out.format("Submission from %s late by %s. Deduct credit? (y/n) ",
                        lookupUser.get(sub.user_id).name, latenessString);
                String deduct = scanner.next();
                if (deduct.equals("y")) {
                    System.out.println("  deducting credit");
                    newScore = origScore * LATE_PENALTY;
                    commentText = String.format(LATE_SUB_TAG + "Original grade: %f", origScore);
                } else {
                    System.out.println("  ignoring submission");
                    continue;
                }

            } else { // submission after extension period, no credit
                System.out.format("*** Submission from %s PAST EXTENSION PERIOD (late by %s). Give a zero? (y/n) ",
                        lookupUser.get(sub.user_id).name, latenessString);
                String deduct = scanner.next();
                if (deduct.equals("y")) {
                    System.out.println("  zeroing");
                    newScore = 0;
                    commentText = String.format(UNACCEPTED_SUB_TAG + "Original grade: %f", origScore);
                } else {
                    System.out.println("  ignoring submission");
                    continue;
                }
            }

            // upload score+comment
            uploadCommentForSubmission(hwId, String.valueOf(sub.user_id), newScore, commentText);

        }

    }

    /**
     * Upload score+comment for a submission
     */
    private static void uploadCommentForSubmission(final String assignmentId, final String studentId,
                                                   final double score, final String comment) throws IOException {
        HttpRequest request;
        GenericUrl url;

        url = new GenericUrl(Common.CourseURL() + "assignments/" + assignmentId + "/submissions/" + studentId);
        url.put("comment[text_comment]", comment);
        url.put("submission[posted_grade]", score);
        url.put("comment[group_comment]", true);

        request = Common.requestFactory.buildPutRequest(url, null);
        request.getHeaders().setAuthorization(Common.TOKEN);
        //request.execute();
    }

}
