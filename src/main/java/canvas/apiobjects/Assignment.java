package canvas.apiobjects;

import canvas.Common;
import com.google.api.client.util.Key;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

public class Assignment {
    @Key
    public int id;
    @Key
    public Integer group_category_id;
    @Key
    public String name;
    @Key
    public boolean published;
    @Key
    public String[] submission_types;
    /** Allowed file extensions */
    @Key
    public String[] allowed_extensions;
    @Key
    public Double points_possible;

    @Key("due_at")
    public String due_at_string;
    public ZonedDateTime due_at;

    @Key("lock_at")
    public String lock_at_string;
    public ZonedDateTime lock_at;

    public void parseTimes() {
        if (!due_at_string.isEmpty()) {
            try {
                due_at = Common.parseCanvasDate(due_at_string);
            } catch (DateTimeParseException e) {
                System.err.format("  *** Invalid due_at_string '%s'", due_at_string);
            }
        }
        if (!lock_at_string.isEmpty()) {
            try {
                lock_at = Common.parseCanvasDate(lock_at_string);
            } catch (DateTimeParseException e) {
                System.err.format("  *** Invalid lock_at_string '%s'", lock_at_string);
            }
        }
    }

    public boolean isGroupAssignment() {
        return group_category_id != null && group_category_id != 0;
    }
}
