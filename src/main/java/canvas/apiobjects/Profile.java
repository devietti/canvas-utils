package canvas.apiobjects;

import com.google.api.client.util.Key;

public class Profile {
    @Key
    public int id;
    @Key
    public String name;
    @Key
    public String primary_email;
}
