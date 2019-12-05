package canvas.archived;

import canvas.Common;
import canvas.apiobjects.Assignment;
import canvas.apiobjects.Group;
import canvas.apiobjects.Submission;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrackExtensionUsage {

    // UPDATE THESE FOR EACH CLASS
    private static final String INDIVIDUAL_EXTENSIONS_ASSIGNMENT_ID = "5449034";
    private static final String GROUP_EXTENSIONS_ASSIGNMENT_ID = "5449028";
    /** Submissions a few minutes after the deadline don't burn an extension */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(15);
    /** Extension duration */
    private static final Duration EXTENSION = Duration.ofHours(49);
    /** */
    private static final List<String> individualHomeworks = Arrays.asList("homework 1:", "homework 2:");

    //
    private static final Map<Integer, User> lookupUser = new HashMap<>();
    private static final Map<User, Integer> IndividualExtensionsUsed = new HashMap<>();
    private static final Map<Group, Integer> GroupExtensionsUsed = new HashMap<>();

    private static void setup() throws IOException {
        Common.setup();

        HttpResponse response;
        GenericUrl url;

        // populate user list
        System.out.println("Getting list of users...");

        url = new GenericUrl(Common.CourseURL() + "users");
        url.put("enrollment_type", "student");
        response = Common.get(url);
        User[] users = response.parseAs(User[].class);
        for (User u : users) {
            lookupUser.put(u.id, u);
        }

        // populate list of groups
        System.out.println("Generating user:group mapping...");

        response = Common.get("groups");
        Group[] groups = response.parseAs(Group[].class);
        for (Group g : groups) {
            if (!g.name.toLowerCase().startsWith("homework group ")) {
                continue;
            }
            response = Common.get(new GenericUrl(Common.BASE_URL + "groups/" + g.id + "/users"));
            User[] groupMembers = response.parseAs(User[].class);
            g.members = new User[groupMembers.length];

            for (int i = 0; i < groupMembers.length; i++) {
                User m = groupMembers[i];
                g.members[i] = lookupUser.get(m.id);
                g.members[i].group = g;
            }
        }

    }

    private static String names(User[] us) {
        String s = "";
        for (int i = 0; i < us.length; i++) {
            s += us[i].name;
            if (i != us.length - 1) {
                s += ", ";
            }
        }
        return s;
    }

    public static void main(String[] args) throws IOException {
        setup();

        final boolean doUpload = args.length > 0 && args[0].equals("-upload");

        HttpResponse response;

        response = Common.get("assignments");
        Assignment[] hws = response.parseAs(Assignment[].class);
        for (Assignment hw : hws) {

            // ignore assignments without due dates, like the extension-tracking assignments, and
            // unpublished assignments
            if (hw.due_at_string.equals("") || !hw.published) {
                continue;
            }

            try {
                hw.due_at = Common.parseCanvasDate(hw.due_at_string);
            } catch (DateTimeParseException e) {
                System.err.format("*** Invalid due date'%s' for hw %s%n",
                        hw.due_at_string, hw.name);
                continue;
            }

            if (!hw.name.toLowerCase().startsWith("homework ")) continue;

            boolean individualHw = false;
            for (String s : individualHomeworks) {
                // NB: hw.name is something like "Homework 1: Assembly Code"
                if (hw.name.toLowerCase().contains(s)) {
                    individualHw = true;
                    break;
                }
            }

            System.out.format("Processing %s (group=%b)...%n", hw.name, !individualHw);

            response = Common.get("assignments/" + hw.id + "/submissions");
            Submission[] subs = response.parseAs(Submission[].class);

            // find most recent submission for each user or group
            Map<Integer, Submission> mostRecentSub = new HashMap<>();
            for (Submission sub : subs) {
                final User u = lookupUser.get(sub.user_id);
                if (individualHw) {
                    sub.users = new User[]{u};
                } else if (u.group == null) { // doing group assignment solo
                    sub.users = new User[]{u};
                    u.group = new Group(); // HACK!! create fake Group for solo folks
                    u.group.id = -1 * u.id;
                    u.group.name = "solo group for " + u.name;
                    u.group.members = new User[]{u};
                } else { // group assignment submitted as part of a group
                    sub.users = u.group.members;
                    assert sub.users != null;
                    assert sub.users[0].group != null;
                }

                sub.parseTimes();
                if (null == sub.submitted_at) continue;

                int subId = individualHw ? sub.user_id : sub.users[0].group.id;
                Submission existing = mostRecentSub.get(subId);
                if (null == existing) {
                    mostRecentSub.put(subId, sub);
                } else { // only insert sub if newer than existing sub
                    if (sub.submitted_at.isAfter(existing.submitted_at)) {
                        mostRecentSub.put(subId, sub);
                    }
                }
            }

            // classify each submission as on-time, late, or super-late
            for (Submission sub : mostRecentSub.values()) {
                if (sub.submitted_at.isAfter(hw.due_at.plus(GRACE_PERIOD))) { // late submission
                    if (sub.submitted_at.isAfter(hw.due_at.plus(EXTENSION))) { // super-late!
                        System.out.format("  *** SUPER-LATE submission of %s from %s%n",
                                hw.name, names(sub.users));
                    } else {
                        System.out.format("  * LATE submission of %s from %s%n",
                                hw.name, names(sub.users));
                    }
                    if (individualHw) {
                        assert 1 == sub.users.length : names(sub.users);
                        int e = IndividualExtensionsUsed.getOrDefault(sub.users[0], 0);
                        IndividualExtensionsUsed.put(sub.users[0], e + 1);
                    } else {
                        Group g = sub.users[0].group;
                        int e = GroupExtensionsUsed.getOrDefault(g, 0);
                        GroupExtensionsUsed.put(g, e + 1);
                    }
                }
            }

        }


        // special hacks

//        // Group "224682" is "Homework Group 24", Anupam Alur and Akshay Sriraman
//        // They turned in HW3 1h late, but I'm not charging them for the extension
//        Group anupamAkshay = null;
//        for (Group g : GroupExtensionsUsed.keySet()) {
//            if (g.id == 224682) {
//                assert g.name.equals("Homework Group 24") : g.name;
//                assert g.members[0].name.equals("Anupam Alur") || g.members[0].name.equals("Anupam Alur");
//                assert g.members[0].name.equals("Anupam Alur") || g.members[1].name.equals("Akshay Sriraman");
//                anupamAkshay = g;
//            }
//        }
//        assert null != anupamAkshay;
//        int used = GroupExtensionsUsed.get(anupamAkshay);
//        GroupExtensionsUsed.put(anupamAkshay, used-1);


        System.out.println("\n\nINDIVIDUAL EXTENSIONS USED:");
        IndividualExtensionsUsed.forEach((u, c) -> System.out.format(" %s %d %n", u.name, c));
        IndividualExtensionsUsed.forEach((u, c) -> {
            if (c > 1) System.out.format(" *** %s used too many individual extensions (%d/1)%n",
                    u.name, c);
        });

        System.out.println("\nGROUP EXTENSIONS USED:");
        GroupExtensionsUsed.forEach((g, c) -> System.out.format(" %s %d %n", names(g.members), c));
        GroupExtensionsUsed.forEach((g, c) -> {
            if (c > 2) System.out.format(" *** %s (%s) used too many group extensions (%d/2)%n",
                    g.name, names(g.members), c);
        });

        if (doUpload) {
            // upload extension usage information for everyone
            System.out.println("\nUploading extension usage...");
            lookupUser.forEach((uid, u) -> {
                System.out.format(" %s...%n", u.name);
                for (final String aid : new String[]{INDIVIDUAL_EXTENSIONS_ASSIGNMENT_ID, GROUP_EXTENSIONS_ASSIGNMENT_ID}) {
                    GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + aid + "/submissions/" + u.id);

                    int extsUsed;
                    if (aid.equals(INDIVIDUAL_EXTENSIONS_ASSIGNMENT_ID)) {
                        extsUsed = IndividualExtensionsUsed.getOrDefault(u, 0);
                    } else {
                        extsUsed = GroupExtensionsUsed.getOrDefault(u.group, 0);
                    }
                    url.put("submission[posted_grade]", extsUsed);

                    try {
                        HttpRequest request = Common.requestFactory.buildPutRequest(url, null);
                        request.getHeaders().setAuthorization(Common.TOKEN);
                        request.execute();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        System.out.println("All done!");

    }

}
