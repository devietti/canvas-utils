package canvas.apiobjects;

import com.google.api.client.util.Key;

import java.time.ZonedDateTime;

public class QuizSubmission {
    @Key
    public int id;
    @Key
    public int quiz_id;
    @Key
    public int user_id;
    @Key("finished_at")
    public String finished_at_string;
    public ZonedDateTime finished_at;
}
