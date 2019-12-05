package canvas.apiobjects;

import com.google.api.client.util.Key;

import java.util.Map;

public class UploadHandlePOST {
    @Key
    public String upload_url;
    @Key
    public Map<String, String> upload_params;
}
