package canvas.apiobjects;

import com.google.api.client.util.Key;

public class QuizQuestion {
    @Key
    public int id;
    @Key
    public int quiz_id;
    @Key
    public String question_name;
}
