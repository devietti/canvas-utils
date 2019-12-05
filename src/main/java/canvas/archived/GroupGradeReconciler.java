package canvas.archived;

import canvas.Common;
import canvas.apiobjects.Group;
import canvas.apiobjects.Submission;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Check that all members of a group have the same grade for a given assignment/quiz.
 */
public class GroupGradeReconciler {

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length > 1) {
            System.out.println("Usage: " + GroupGradeReconciler.class.getName() + " [group name filter]");
            return;
        }
        final String groupNameFilter = 1 == args.length ? args[0] : "";

        Common.setup();

        // TODO: support quizzes as well

        final String HW_ID = Common.pickAssignment("Lab");

        // build list of groups
        List<Group> groups = Common.getAsList("groups", Group[].class);
        List<Group> groupsToProcess = new LinkedList<>();
        for (Group g : groups) {
            if (!g.name.toLowerCase().contains(groupNameFilter)) {
                continue;
            }

            List<User> groupMembers = Common.getAsList(new GenericUrl(Common.BASE_URL + "groups/" + g.id + "/users"), User[].class);
            if (0 == groupMembers.size()) { // skip empty groups
                continue;
            }

            g.members = groupMembers.toArray(new User[]{});
            groupsToProcess.add(g);
        }

        // build map of user id => Submission
        Map<Integer, Submission> subOfUser = new HashMap<>();
        List<Submission> subs = Common.getAsList("assignments/" + HW_ID + "/submissions", Submission[].class);
        // NB: for LTI submissions, I think Canvas only tracks the latest one
        for (Submission s : subs) {
            subOfUser.put(s.user_id, s);
        }

        // walk over groups, checking that each member has the same grade
        for (Group g : groupsToProcess) {

            // get scores for each member
            List<Double> scores = Arrays.stream(g.members)
                    .map(m -> subOfUser.containsKey(m.id) ? subOfUser.get(m.id).score : 0.0 )
                    .collect(Collectors.toList());

            // check that all scores are the same
            boolean allSame = (1 == scores.stream().distinct().count());

            if (!allSame) { // give all members the max score
                final double maxScore = scores.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
                List<String> updateNames = Arrays.stream(g.members)
                        .filter(m -> maxScore != subOfUser.get(m.id).score)
                        .map(m -> m.name)
                        .collect(Collectors.toList());
                List<String> updateScores = Arrays.stream(g.members)
                        .filter(m -> maxScore != subOfUser.get(m.id).score)
                        .map(m -> String.format("%f", subOfUser.get(m.id).score))
                        .collect(Collectors.toList());

                System.out.format("%s should have score %.2f (instead of %s) %s %n",
                        updateNames.toString(), maxScore, updateScores.toString(), scores.toString());
            }

        }

    } // end main()
}
