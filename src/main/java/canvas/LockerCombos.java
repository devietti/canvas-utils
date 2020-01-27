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
            // these combos are from Spring 2020
            "0-26-8",
            "13-27-21",
            "14-24-10",
            "1-23-37",
            "5-19-29",
            "25-31-37",
            "33-7-21",
            "17-7-13",
            "17-27-21",
            "28-18-28",
            "29-15-29",
            "20-6-32",
            "20-38-8",
            "0-30-4",
            "38-0-22",
            "34-16-30",
            "6-16-22",
            "11-37-23",
            "33-7-17",
            "24-26-4",
            "8-10-24",
            "10-32-22",
            "19-25-35",
            "23-29-23",
            "19-25-7",
            "26-16-6",
            "34-24-34",
            "10-12-38",
            "27-9-3",
            "19-9-19"
    };

    public static void main(String[] args) throws IOException {
        Common.setup();

        // assign lockers to students in round-robin order

        List<User> users = Common.getAsList("students", User[].class);
        System.out.format("lockers: %d students: %d %n", COMBOS.length, users.size());
        int lockerIndex = 0;
        for (User u : users) {
            String combo = COMBOS[lockerIndex];
            int lockerNum = lockerIndex+1;

            System.out.format("Locker %d: %s %n", lockerNum, u.name);

            Conversation conv = new Conversation();
            conv.recipients = new int[] {u.id};
            conv.subject = "CIS 371 ZedBoard Locker info";
            conv.body = String.format("Your ZedBoard locker is #%d. The combo is %s.", lockerNum, combo);
            //System.out.format("%s %s %n", conv.recipients.toString(), conv.body);
            GenericUrl post = new GenericUrl(Common.BASE_URL + "conversations");
            Common.postJSON(post, conv, null);

            lockerIndex = (lockerIndex+1) % COMBOS.length;
        }

        /*
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
        */

    }

}
