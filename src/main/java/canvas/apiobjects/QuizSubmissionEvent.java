package canvas.apiobjects;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;

public class QuizSubmissionEvent extends GenericJson {
    @Key
    public int id;
    @Key
    public String event_type;
    @Key
    public String created_at_string;
}
