package canvas.apiobjects;

import com.google.api.client.util.Key;

public class Conversation {
    @Key
    public int[] recipients;
    @Key
    public String subject;
    @Key
    public String body;
    @Key
    public boolean group_conversation;
    @Key
    public int[] attachment_ids;
}
