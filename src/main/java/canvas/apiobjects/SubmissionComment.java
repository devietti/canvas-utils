package canvas.apiobjects;

import com.google.api.client.util.Key;

public class SubmissionComment {
    @Key
    public int id;
    @Key
    public int author_id;
    @Key
    public String author_name;
    @Key
    public String comment;
    @Key
    public Attachment[] attachments;

    @Override
    public String toString() {
        return author_name + " said: '" + comment + "'";
    }
}
