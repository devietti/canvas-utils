package canvas.apiobjects;

import canvas.Common;
import com.google.api.client.util.Key;

import java.time.ZonedDateTime;

public class CalendarEvent {
    @Key
    public int id;
    @Key
    public String title;
    @Key
    public String description;
    @Key
    public String location_name;
    @Key
    public String context_code;

    @Key("start_at")
    public String start_at_string;
    public ZonedDateTime start_at;
    @Key("end_at")
    public String end_at_string;
    public ZonedDateTime end_at;

    public void initTimeFields() {
        this.start_at = Common.parseCanvasDate(this.start_at_string);
        this.end_at = Common.parseCanvasDate(this.end_at_string);
    }
}
