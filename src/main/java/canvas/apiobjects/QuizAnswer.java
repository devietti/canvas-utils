package canvas.apiobjects;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;

public class QuizAnswer extends GenericJson {
    @Key
    public int quiz_question_id;
}
