package canvas;

import canvas.apiobjects.Assignment;

import javax.json.*;
import javax.json.stream.JsonGenerator;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AssignmentMetadata {
    final int assignmentId;
    final String shortName;
    final String fileToSubmit;
    final boolean groupAssignment;
    /**
     * If fileToSubmit is a .zip file, this array gives the file names that must be present in the archive. If
     * fileToSubmit is not a .zip, archiveContents is null.
     */
    final String[] archiveContents;

    AssignmentMetadata(int aid, String sn, String fts, String[] ac, boolean ga) {
        this.assignmentId = aid;
        this.shortName = sn;
        this.fileToSubmit = fts;
        this.archiveContents = ac;
        this.groupAssignment = ga;
    }
}

public class CreateAssignmentDB {

    private final static String LTI_ROOT_DIR = "/home1/c/cis371/submissions/";
    private final static String AG_ROOT_DIR = "/home/ubuntu/submissions/";
    private final static AssignmentMetadata[] ASSN_METADATA = new AssignmentMetadata[]{
            new AssignmentMetadata(6204809, "sandbox", "sandbox.v", null, false),
            new AssignmentMetadata(6235249, "sandbox-group", "sandbox.v", null, true),
            new AssignmentMetadata(6160743, "lab1", "rca.v", null, false),
            new AssignmentMetadata(6197936, "lab2div", "lc4_divider.v", null, false),
            new AssignmentMetadata(6197947, "lab2alu", "alu.zip", new String[]{"lc4_divider.v", "lc4_alu.v"}, false),
            new AssignmentMetadata(6197975, "lab3alu", "single.zip",
                    new String[]{"lc4_regfile.v", "lc4_single.v", "lc4_divider.v", "lc4_alu.v"}, true),
            new AssignmentMetadata(6197976, "lab3full", "single.zip",
                    new String[]{"lc4_regfile.v", "lc4_single.v", "lc4_divider.v", "lc4_alu.v"}, true),
            new AssignmentMetadata(6197985, "lab4alu", "pipeline.zip",
                    new String[]{"lc4_regfile.v", "lc4_pipeline.v", "lc4_divider.v", "lc4_alu.v"}, true),
            new AssignmentMetadata(6197987, "lab4full", "pipeline.zip",
                    new String[]{"lc4_regfile.v", "lc4_pipeline.v", "lc4_divider.v", "lc4_alu.v"}, true),
            new AssignmentMetadata(6197999, "lab5alu", "superscalar.zip",
                    new String[]{"lc4_regfile_ss.v", "lc4_superscalar.v", "lc4_divider.v", "lc4_alu.v"}, true),
            new AssignmentMetadata(6198000, "lab5full", "superscalar.zip",
                    new String[]{"lc4_regfile_ss.v", "lc4_superscalar.v", "lc4_divider.v", "lc4_alu.v"}, true),
    };

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length != 1) {
            System.out.println("Usage: " + CreateGroupDB.class.getName() + " <assignments.json>");
            return;
        }
        final String JSON_FILE = args[0];
        PrintWriter w = new PrintWriter(new FileWriter(JSON_FILE));

        // populate list of assignments
        System.out.println("Getting list of assignments...");

        Common.setup();

        List<Assignment> assignments = Common.getAsList("assignments", Assignment[].class);

        JsonObjectBuilder jobOuter = Json.createObjectBuilder();

        for (Assignment a : assignments) {

            AssignmentMetadata amd = null;
            for (AssignmentMetadata md : ASSN_METADATA) {
                if (md.assignmentId == a.id) {
                    amd = md;
                }
            }
            if (null == amd) {
                System.out.println("Couldn't find metadata for " + a.name + ". Skipping...");
                continue;
            }

            JsonObjectBuilder jobA = Json.createObjectBuilder()
                    .add("AssignmentName", a.name)
                    .add("ShortName", amd.shortName)
                    .add("FileToSubmit", amd.fileToSubmit)
                    .add("GroupAssignment", amd.groupAssignment);

            if (null != amd.archiveContents) {
                JsonArrayBuilder jab = Json.createArrayBuilder(Arrays.asList(amd.archiveContents));
                jobA.add("ArchiveContents", jab);
            } else {
                jobA.addNull("ArchiveContents");
            }

            // used by LTI tool on fling
            jobA.add("LTISubmittedFilesDir", Paths.get(LTI_ROOT_DIR, amd.shortName, "submitted-files").toString() + '/');
            jobA.add("ScriptLogsDir", Paths.get(LTI_ROOT_DIR, amd.shortName, "script-logs").toString() + '/');

            // used by autograder on EC2
            jobA.add("AGSubmittedFilesDir", Paths.get(AG_ROOT_DIR, amd.shortName, "submitted-files").toString() + '/');
            jobA.add("SimLogsDir", Paths.get(AG_ROOT_DIR, amd.shortName, "sim-logs").toString() + '/');
            jobA.add("SynthLogsDir", Paths.get(AG_ROOT_DIR, amd.shortName, "synth-logs").toString() + '/');
//            jobA.add("JsonDir", Paths.get(LTI_ROOT_DIR, amd.shortName, "json").toString() + '/');

            //w.println(jobA.build().toString());
            jobOuter.add(String.valueOf(a.id), jobA);
        }

        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
        JsonWriter jsonWriter = writerFactory.createWriter(w);
        jsonWriter.writeObject(jobOuter.build());
        jsonWriter.close();
        w.close();

    } // end main()
}
