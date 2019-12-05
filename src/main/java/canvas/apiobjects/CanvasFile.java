package canvas.apiobjects;

import com.google.api.client.util.Key;

public class CanvasFile {
    @Key
    public int id;
    @Key
    public String filename;
    @Key
    public int size;
    /** Not part of the File object, but shows up during file uploads */
    @Key
    public String workflow_state;
}
