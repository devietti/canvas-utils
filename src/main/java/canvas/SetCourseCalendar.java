package canvas;

import canvas.apiobjects.CalendarEvent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.UrlEncodedContent;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.util.FixedUidGenerator;
import net.fortuna.ical4j.util.MapTimeZoneCache;
import net.fortuna.ical4j.util.UidGenerator;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;

enum EventType {CLASS, OFFICE_HOURS, EXAM, REVIEW_SESSION, INFO_SESSION}

/**
 * Use for class, office hours
 */
class RepeatingCourseEvent {
    final String name;
    final EventType eventType;
    final String location;
    final LocalTime startTime;
    final Duration duration;
    final DayOfWeek[] days;
    final LocalDate firstDay;
    final LocalDate lastDay;
    /**
     * No event on these dates.
     */
    final LocalDate[] exceptOn;

    RepeatingCourseEvent(String name, EventType etype, String location, LocalTime start, Duration duration,
                         DayOfWeek[] days, LocalDate firstDay, LocalDate lastDay, LocalDate[] except) {
        this.name = name;
        this.eventType = etype;
        this.location = location;
        this.startTime = start;
        this.duration = duration;
        this.days = days;
        this.firstDay = firstDay;
        this.lastDay = lastDay;
        this.exceptOn = except;
    }
}

/**
 * Use for review sessions
 */
class OneOffCourseEvent {
    final String name;
    final String location;
    final LocalDateTime start;
    final Duration duration;

    OneOffCourseEvent(String name, String location, LocalDateTime start, Duration duration) {
        this.name = name;
        this.location = location;
        this.start = start;
        this.duration = duration;
    }
}

/**
 * Setup the Canvas Calendar for this course to include all non-office-hour events: class meetings, exams, etc.
 *
 * Create a separate .ics file with all office hours.
 */
public class SetCourseCalendar {

    /**
     * NB: Canvas will strip invalid HTML tags, so can't use angle brackets in the tag
     */
    private static final String AUTOGEN_TAG = "[auto-generated]";
    private static final int THE_YEAR = 2019;
    private static final String TIMEZONE = "America/New_York";
    private static final Calendar OH_CAL = new Calendar();
    private static final String OH_ICAL_FILE = "cis501oh.ics";

    // current semester information
    private static final LocalDate FIRST_DAY = LocalDate.of(THE_YEAR, Month.AUGUST, 28);
    private static final LocalDate LAST_DAY_OF_CLASSES = LocalDate.of(THE_YEAR, Month.DECEMBER, 9);
    private static final LocalDate LAST_DAY_OF_SEMESTER = LocalDate.of(THE_YEAR, Month.DECEMBER, 19);
    private static final LocalDate[] BREAKS = new LocalDate[]{
            LocalDate.of(THE_YEAR, Month.SEPTEMBER, 2), // Labor Day
            LocalDate.of(THE_YEAR, Month.OCTOBER, 10), // Fall Break
            LocalDate.of(THE_YEAR, Month.OCTOBER, 11), // Fall Break
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 27), // pre-Thanksgiving schedule switch
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 28), // Thanksgiving
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 29) // Thanksgiving
    };

    // class/OH schedule
    private static final RepeatingCourseEvent[] REPEATING_COURSE_EVENTS = new RepeatingCourseEvent[]{
            new RepeatingCourseEvent("CIS 501", EventType.CLASS, "Moore 216",
                    LocalTime.of(13, 30), Duration.ofMinutes(90),
                    new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY},
                    FIRST_DAY, LAST_DAY_OF_CLASSES, null),

            /*new RepeatingCourseEvent("CIS 501 OH (Sam)", EventType.OFFICE_HOURS, "Levine 5th floor bump space",
                    LocalTime.of(11, 0), Duration.ofMinutes(60),
                    new DayOfWeek[]{DayOfWeek.MONDAY},
                    LocalDate.of(THE_YEAR, Month.SEPTEMBER, 10), LAST_DAY_OF_CLASSES,
                    null),
            new RepeatingCourseEvent("CIS 501 OH (Sam)", EventType.OFFICE_HOURS, "Levine 5th floor bump space",
                    LocalTime.of(13, 0), Duration.ofMinutes(120),
                    new DayOfWeek[]{DayOfWeek.THURSDAY},
                    LocalDate.of(THE_YEAR, Month.SEPTEMBER, 9), LAST_DAY_OF_CLASSES,
                    null),*/
            new RepeatingCourseEvent("CIS 501 OH (Alex)", EventType.OFFICE_HOURS, "Levine 5th floor bump space",
                    LocalTime.of(15, 0), Duration.ofMinutes(90),
                    new DayOfWeek[]{DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY},
                    LocalDate.of(THE_YEAR, Month.SEPTEMBER, 16), LAST_DAY_OF_CLASSES,
                    null),
            new RepeatingCourseEvent("CIS 501 OH (Grant)", EventType.OFFICE_HOURS, "Ketterer Lab (Moore 200)",
                    LocalTime.of(15, 0), Duration.ofMinutes(90),
                    new DayOfWeek[]{DayOfWeek.TUESDAY,DayOfWeek.THURSDAY},
                    LocalDate.of(THE_YEAR, Month.SEPTEMBER, 11), LAST_DAY_OF_CLASSES,
                    null),
            new RepeatingCourseEvent("CIS 501 OH (Joe)", EventType.OFFICE_HOURS, "Levine 572",
                    LocalTime.of(10, 0), Duration.ofMinutes(90),
                    new DayOfWeek[]{DayOfWeek.THURSDAY},
                    LocalDate.of(THE_YEAR, Month.SEPTEMBER, 9), LAST_DAY_OF_CLASSES,
                    null),
            new RepeatingCourseEvent("CIS 501 OH (Brandon)", EventType.OFFICE_HOURS, "K Lab",
                    LocalTime.of(20, 0), Duration.ofMinutes(180),
                    new DayOfWeek[]{DayOfWeek.SUNDAY},
                    LocalDate.of(THE_YEAR, Month.OCTOBER, 6), LocalDate.of(THE_YEAR, Month.OCTOBER, 13),
                    null),
            new RepeatingCourseEvent("CIS 501 OH (Brandon)", EventType.OFFICE_HOURS, "K Lab",
                    LocalTime.of(18, 0), Duration.ofMinutes(180),
                    new DayOfWeek[]{DayOfWeek.SUNDAY},
                    LocalDate.of(THE_YEAR, Month.OCTOBER, 15), LAST_DAY_OF_CLASSES,
                    null),
    };

    // one-off events like review sessions and exams
    private static final OneOffCourseEvent[] ONE_OFF_COURSE_EVENTS = new OneOffCourseEvent[]{


            /*
            new OneOffCourseEvent("CIS 501 OH (Joe)", "K Lab",
                    LocalDateTime.of(THE_YEAR, Month.APRIL, 5, 13, 0), Duration.ofHours(1)),
            new OneOffCourseEvent("CIS 501 OH (Joe)", "K Lab",
                    LocalDateTime.of(THE_YEAR, Month.APRIL, 12, 11, 0), Duration.ofHours(1)),
            new OneOffCourseEvent("CIS 501 OH (Joe)", "K Lab",
                    LocalDateTime.of(THE_YEAR, Month.APRIL, 26, 11, 0), Duration.ofHours(1)),
                    */

            new OneOffCourseEvent("CIS 501 Midterm Exam", "Moore 216",
                    LocalDateTime.of(THE_YEAR, Month.OCTOBER, 2, 13, 30), Duration.ofMinutes(90)),
            new OneOffCourseEvent("CIS 501 Final Exam", "DRL A8",
                    LocalDateTime.of(THE_YEAR, Month.DECEMBER, 16, 12, 0), Duration.ofHours(2))
    };

    private static final String[] CLASS_TOPICS = new String[]{
            "Introduction",
            "Verilog (ILS Ch 1)", "Verilog (ILS Ch 1)",
            "ALU (COD Ch 3)", "ALU (COD Ch 3)",
            "ISAs",
            "Datapath (COD Ch 4.1-4.4)", "Datapath (COD Ch 4)",
            "Rosh Hashana (no class)",
            "Midterm Exam (in class)",
            "Performance (COD Ch 7.10)",
            "Yom Kippur (no class)",
            "Debugging Strategies",
            "Pipeline (COD Ch 4)", "Pipeline (COD Ch 4)",
            "Branch Prediction (COD Ch 4)",
            "Caches (COD Ch 5.1-5.2)", "Caches (COD Ch 5.1-5.2)", "Caches (COD Ch 5.3)",
            "Superscalar (COD pp 391-7)", "Superscalar",
            "Out-of-Order (COD pp 397-402)", "Out-of-Order",
            //"Memory (COD Ch 5.4)",
            "DRAM, Spectre/Meltdown Attacks",
            "Multicore (COD Ch 7.1-7.3)",
            "Multicore (COD Ch 7.1-7.3)",
            "GPUs (COD Ch 7.7)", "TPUs and accelerators"
    };

    public static void main(String[] args) throws IOException {

        Common.setup();

        // setup OH calendar
        OH_CAL.getProperties().add(new ProdId("-//CIS 501 Office Hours//iCal4j 1.0//EN"));
        OH_CAL.getProperties().add(Version.VERSION_2_0);
        OH_CAL.getProperties().add(CalScale.GREGORIAN);
        UidGenerator ug = new FixedUidGenerator("uidGen");
        // magic to disable ical4j's fancy tz caching: https://github.com/ical4j/ical4j/issues/195
        System.setProperty("net.fortuna.ical4j.timezone.cache.impl", MapTimeZoneCache.class.getName());
        TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
        TimeZone timezone = registry.getTimeZone(TIMEZONE);
        VTimeZone tz = timezone.getVTimeZone();

        // 1) get all events from Canvas calendar

        GenericUrl url;

        url = new GenericUrl(Common.BASE_URL + "calendar_events");
        url.put("type", "event");
        url.put("context_codes[]", "course_" + Common.CourseID());
        //url.put("context_codes[]", "user_4888227");
        url.put("all_events", "true");
        System.out.println("Fetching existing calendar events...");

        List<CalendarEvent> calendarEvents = Common.getAsList(url, CalendarEvent[].class);

        // for (CalendarEvent ce : calendarEvents) {
        //     ce.initTimeFields();
        //     System.out.println(":" + ce.title + ":" + ce.description + ":" + ce.id);
        // }

        // 2) remove all auto-generated events from Canvas Calendar
        System.out.println("Removing auto-generated calendar events...");
        for (CalendarEvent ce : calendarEvents) {
            if (ce.description.contains(AUTOGEN_TAG)) {
                url = new GenericUrl(Common.BASE_URL + "calendar_events/" + ce.id);
                try {
                    HttpRequest request = Common.requestFactory.buildDeleteRequest(url);
                    request.getHeaders().setAuthorization(Common.TOKEN);
                    request.execute();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // 3) re-populate auto-generated events
        System.out.println("Re-populating calendar events...");

        // go day-by-day through the semester
        int cti = 0;
        for (LocalDate d = FIRST_DAY; d.isBefore(LAST_DAY_OF_SEMESTER); d = d.plusDays(1)) {
            final LocalDate d_ = d;

            // we're on break
            if (Arrays.stream(BREAKS).anyMatch(b -> b.isEqual(d_))) {
                continue;
            }

            for (RepeatingCourseEvent ce : REPEATING_COURSE_EVENTS) {
                if (Arrays.stream(ce.days).noneMatch(cd -> cd.equals(d_.getDayOfWeek())) ||
                        d.isBefore(ce.firstDay) ||
                        d.isAfter(ce.lastDay) ||
                        (null != ce.exceptOn && Arrays.asList(ce.exceptOn).contains(d_))
                        ) {
                    // no event today
                    continue;
                }

                // if we reach here, we have an event
                String eventName = ce.name;
                if (EventType.CLASS == ce.eventType) {
                    eventName += ": " + CLASS_TOPICS[cti];
                    cti++;
                }

                if (EventType.OFFICE_HOURS != ce.eventType) {
                    LocalDateTime eventStart = d.atTime(ce.startTime);
                    addEventToCanvas(new OneOffCourseEvent(eventName, ce.location, eventStart, ce.duration));
                } else { // put OH on the OH calendar
                    VEvent oh = new VEvent(new DateTime(jdate(ce.startTime,d_)), ce.duration, ce.name);
                    Location place = new Location(ce.location);
                    oh.getProperties().add(place);
                    oh.getProperties().add(ug.generateUid());
                    oh.getProperties().add(tz.getTimeZoneId());
                    OH_CAL.getComponents().add(oh);
                }
            }

            for (OneOffCourseEvent ce : ONE_OFF_COURSE_EVENTS) { // 1-off events go to Canvas calendar
                if (!d.isEqual(ce.start.toLocalDate())) {
                    // no event today
                    continue;
                }

                // if we reach here, we have an event
                addEventToCanvas(ce);
            }


        }
        assert cti == CLASS_TOPICS.length : "only consumed " + cti + " of " + CLASS_TOPICS.length + " class topics!";

        if (0 != OH_CAL.getComponents().size()) {
            // write out the OH calendar
            FileOutputStream fout = new FileOutputStream(OH_ICAL_FILE);
            CalendarOutputter outputter = new CalendarOutputter();
            outputter.output(OH_CAL, fout);

            // scp OH calendar to course website
            try {
                ProcessBuilder scp = new ProcessBuilder("bash", "-c", "scp " + OH_ICAL_FILE + " 501:public_html/current/");
                Common.check_call(scp);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static java.util.Date jdate(LocalTime lt, LocalDate ld) {
        return new java.util.Calendar.Builder()
                .setDate(ld.getYear(), ld.getMonthValue()-1, ld.getDayOfMonth())
                .setTimeOfDay(lt.getHour(), lt.getMinute(), lt.getSecond())
                .setTimeZone(TimeZone.getTimeZone(TIMEZONE))
                .build().getTime();
    }

    private static void addEventToCanvas(OneOffCourseEvent ce) {
        // add timezones, because Canvas wants event times in UTC
        final ZoneId tz = ZoneId.of(TIMEZONE);
        final ZonedDateTime eventStart = ZonedDateTime.of(ce.start, tz);
        final ZonedDateTime eventEnd = ZonedDateTime.of(ce.start.plus(ce.duration), tz);

        GenericUrl url = new GenericUrl(Common.BASE_URL + "calendar_events");

        Map<String, String> map = new HashMap<>();
        map.put("calendar_event[context_code]", "course_" + Common.CourseID());
        map.put("calendar_event[title]", ce.name);
        map.put("calendar_event[description]", AUTOGEN_TAG);
        map.put("calendar_event[start_at]", ISO_INSTANT.format(eventStart));
        map.put("calendar_event[end_at]", ISO_INSTANT.format(eventEnd));
        map.put("calendar_event[location_name]", ce.location);
        System.out.println(map);
        UrlEncodedContent content = new UrlEncodedContent(map);

        try {
            HttpRequest request = Common.requestFactory.buildPostRequest(url, content);
            request.getHeaders().setAuthorization(Common.TOKEN);
            request.execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
