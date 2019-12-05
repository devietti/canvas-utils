package canvas.apiobjects;

import com.google.api.client.util.Key;

public class Attachment {
    @Key
    public int id;
    @Key
    public String display_name;
    @Key
    public String filename;
    @Key("content-type")
    public String content_type;
    @Key
    public String url;
    @Key
    public int size;
}
