package canvas.apiobjects;

import canvas.Common;
import com.google.api.client.util.Key;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

public class Submission {
    @Key("submitted_at")
    public String submitted_at_string;
    @Key("graded_at")
    public String graded_at_string;
    @Key
    public int user_id;
    @Key
    public Group group;
    @Key
    public int assignment_id;
    @Key
    public Integer attempt;
    @Key
    public Double score;
    @Key
    public int seconds_late;
    @Key
    public SubmissionComment[] submission_comments;
    @Key
    public Attachment[] attachments;

    // fields for our use
    public ZonedDateTime submitted_at, graded_at;
    public User[] users;

    public void parseTimes() {
        if (!submitted_at_string.isEmpty()) {
            try {
                submitted_at = Common.parseCanvasDate(submitted_at_string);
            } catch (DateTimeParseException e) {
                System.err.format("  *** Invalid submitted_at_string '%s'", submitted_at_string);
            }
        }
        if (!graded_at_string.isEmpty()) {
            try {
                graded_at = Common.parseCanvasDate(graded_at_string);
            } catch (DateTimeParseException e) {
                System.err.format("  *** Invalid graded_at_string '%s'", graded_at_string);
            }

        }
    }

    @Override
    public String toString() {
        return String.format("user id: %d; assignment: %d, group: %s; attempt: %d; submitted at: %s; graded at: %s; score: %.2f;",
                user_id, assignment_id, group.toString(), attempt, submitted_at_string, graded_at_string, score);
    }
}
