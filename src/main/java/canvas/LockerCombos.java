package canvas;

import canvas.apiobjects.Conversation;
import canvas.apiobjects.Group;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.gson.GsonFactory;

import java.io.IOException;
import java.util.List;

public class LockerCombos {

    // NB: locker number is (index+1)
    final private static String[] COMBOS = new String[]{
            // these combos are from Fall 2019
        "33-7-21",
        "38-0-22",
        "17-7-13",
        "6-16-22",
        "20-6-32",
        "0-26-8",
        "17-27-21",
        "29-15-29",
        "13-27-21",
        "28-18-28",
        "34-16-30",
        "14-24-10",
        "11-37-23",
        "20-38-8",
        "10-32-22",
        "33-7-17",
        "23-29-23",
        "0-30-4",
        "1-23-37",
        "19-25-7",
        "26-16-6",
        "5-19-29",
        "25-31-37",
        "10-12-38",
        "8-10-24",
        "27-9-3",
        "19-25-35",
        "19-9-19",
        "34-24-34", // unallocated
        "24-26-4", // unallocated
    };

    public static void main(String[] args) throws IOException {
        Common.setup();

        /* assign slices of students to each locker

        List<User> users = Common.getAsList("students", User[].class);
        System.out.format("%d %d %n", COMBOS.length, users.size());
        // assign lockers to students
        int studentsPerLocker = (users.size() / COMBOS.length)+1;
        for (int i = 0; i < COMBOS.length; i++) {
            String combo = COMBOS[i];
            int lockerNum = i+1;

            int startIndex = Math.min(users.size()-1, i*studentsPerLocker);
            int stopIndex = Math.min(users.size()-1, (i*studentsPerLocker)+studentsPerLocker);
            List<User> studentsForThisLocker = users.subList(startIndex, stopIndex);

            System.out.format("Locker %d: %s %n", lockerNum, studentsForThisLocker.toString());

            Conversation conv = new Conversation();
            conv.recipients = studentsForThisLocker.stream().mapToInt(s -> s.id).toArray();
            conv.subject = "CIS 501 ZedBoard Locker info";
            conv.body = String.format("Your locker is #%d. The combo is %s.", lockerNum, combo);
            //JsonHttpContent content = new JsonHttpContent(new GsonFactory(), conv);
            System.out.format("%s %s %n", conv.recipients.toString(), conv.body);
            GenericUrl post = new GenericUrl(Common.BASE_URL + "conversations");
            Common.postJSON(post, conv, null);
        }
        */

        // assign one locker to each group
        int nextFreeComboIdx = 0;
        List<Group> groups = Common.getAsList("groups", Group[].class);
        for (Group g : groups) {
            if (!g.name.toLowerCase().contains("lab group")) {
                continue;
            }

            List<User> groupMembers = Common.getAsList(new GenericUrl(Common.BASE_URL + "groups/" + g.id + "/users"), User[].class);
            if (0 == groupMembers.size()) { // skip empty groups
                continue;
            }

            System.out.format("Locker %d assigned to %s %n", nextFreeComboIdx, g.name);
            int lockerNumber = nextFreeComboIdx+1;

            Conversation conv = new Conversation();
            conv.recipients = groupMembers.stream().mapToInt(s -> s.id).toArray();
            conv.subject = "CIS 501 ZedBoard Locker info";
            conv.body = String.format("Your locker is #%d. The combo is %s. To open the lock you spin clockwise to the first number, then counter-clockwise past the 2nd number and stop when you hit it again, then clockwise to the 3rd number.", lockerNumber, COMBOS[nextFreeComboIdx]);
            //JsonHttpContent content = new JsonHttpContent(new GsonFactory(), conv);
            System.out.format("%s %s %n", groupMembers.toString(), conv.body);
            GenericUrl post = new GenericUrl(Common.BASE_URL + "conversations");
            Common.postJSON(post, conv, null);

            nextFreeComboIdx++;
        }

    }

}
