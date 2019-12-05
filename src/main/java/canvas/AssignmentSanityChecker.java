package canvas;

import canvas.apiobjects.Assignment;

import java.io.IOException;
import java.util.List;

/** Checks various aspects of a course's assignments (lock dates, group settings)
 *  to make sure they are set correctly. */
public class AssignmentSanityChecker {

    private static void shouldbe(boolean expected, Assignment asn, String msg) {
        if (!expected) {
            System.err.format("  Problem with assignment '%s': %s%n", asn.name, msg);
        }
    }

    public static void main(String[] args) throws IOException {

        Common.setup();

        List<Assignment> assignments = Common.getAsList("assignments", Assignment[].class);

        for (Assignment asn : assignments) {
            String lower = asn.name.toLowerCase();

            if (!lower.startsWith("lab")) {
                continue;
            }
            System.out.format("Processing %s...%n", asn.name);

            // check group assignment settings
            if (lower.startsWith("lab 1")) {
                shouldbe(asn.group_category_id == 0, asn, "should be individual assignment");
            } else { // ≥ Lab 2
                shouldbe(asn.group_category_id != 0, asn, "should be group assignment");
            }

            if (lower.contains("demo")) {
                shouldbe(asn.due_at_string.equals(""), asn, "demos have no due date");
                shouldbe(asn.lock_at_string.equals(""), asn, "demos have no lock date");
                shouldbe(1 == asn.submission_types.length, asn, "wrong submission_types");
                shouldbe(asn.submission_types[0].equals("none"), asn, "wrong submission_types");
            }

            if (lower.contains("code") || lower.contains("schematic")) {
                asn.parseTimes();
                shouldbe(asn.due_at.plusHours(48).equals(asn.lock_at), asn, "should lock after 48 hours");
                shouldbe(1 == asn.submission_types.length, asn, "wrong submission_types");
                shouldbe(asn.submission_types[0].equals("online_upload"), asn, "wrong submission_types");
                shouldbe(1 == asn.allowed_extensions.length, asn, "wrong allowed_extensions");

                if (lower.contains("code")) {
                    if (lower.contains("lab 2: divider code")) {
                        shouldbe(asn.allowed_extensions[0].equals("v"), asn, "wrong allowed_extensions");
                    } else {
                        shouldbe(asn.allowed_extensions[0].equals("zip"), asn, "wrong allowed_extensions");
                    }
                } else {
                    shouldbe(asn.allowed_extensions[0].equals("pdf"), asn, "wrong allowed_extensions");
                }
            }
        }

    }

}
