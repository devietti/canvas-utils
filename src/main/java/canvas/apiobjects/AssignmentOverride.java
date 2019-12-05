package canvas.apiobjects;

import com.google.api.client.util.Key;

import java.time.ZonedDateTime;

public class AssignmentOverride {
    @Key
    public int id;
    @Key
    public int assignment_id;
    @Key
    public int[] student_ids;
    @Key
    public int group_id;

    @Key("due_at")
    public String due_at_string;
    public ZonedDateTime due_at;

    @Key("lock_at")
    public String lock_at_string;
    public ZonedDateTime lock_at;
}
