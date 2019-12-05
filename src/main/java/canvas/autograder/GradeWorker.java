package canvas.autograder;

import canvas.Common;
import com.google.api.client.json.gson.GsonFactory;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class StreamConsumer extends Thread {

    final private BufferedInputStream istream;
    final private File file;

    final private int MAX_BUF_SIZE = 2048; // in bytes
    final private byte[] curBuf = new byte[MAX_BUF_SIZE];
    private int bufVirtualIndex = 0;

    StreamConsumer(InputStream is, File f) {
        istream = new BufferedInputStream(is);
        file = f;
    }

    @Override
    public void run() {
        while (true) {
            try {
                final int bufRealIndex = bufVirtualIndex % curBuf.length;
                int bytesRead = istream.read(curBuf, bufRealIndex, curBuf.length - bufRealIndex);
                if (-1 == bytesRead) { // EOF
                    writeToDisk();
                    return;
                }
                bufVirtualIndex += bytesRead;
            } catch (IOException ioe) {
                try { writeToDisk(); } catch (IOException e) {} // dump what we can
                GradeWorker.LOG.warning(Common.t2s(ioe,"StreamConsumer: error processing stream"));
                return;
            } finally {
                try {istream.close();} catch (IOException e) {}
            }
        }
    }

    private void writeToDisk() throws IOException {
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
            final int bufRealIndex = bufVirtualIndex % curBuf.length;
            // oldest part of buffer
            if (bufVirtualIndex > curBuf.length) {
                String s = "[output truncated]\n";
                bos.write(s.getBytes());
                bos.write(curBuf, bufRealIndex, curBuf.length - bufRealIndex);
            }
            // newest part of buffer
            bos.write(curBuf, 0, bufRealIndex);

            bos.flush();
        }
    }
}

public class GradeWorker {

    public static final String REPO_URL = "https://github.com/upenn-acg/cis501.git";
    private static final String REPO_WC_NAME = "gittmp";

    final static Logger LOG = Logger.getLogger("GradeWorker");

    private static final OptionSpec<File> SubmittedFile;
    private static final OptionSpec<File> SubmissionDir;
    private static final OptionSpec<Integer> AssignmentID;
    private static final OptionSpec Help;
    private static final OptionParser Parser;
    private static OptionSet Options;

    static {
        Parser = new OptionParser();
        SubmittedFile = Parser.accepts("submitted-file", "File submitted by student").withRequiredArg().ofType(File.class).required();
        SubmissionDir = Parser.accepts("submission-dir", "Directory in which to run this submission").withRequiredArg().ofType(File.class).required();
        AssignmentID = Parser.accepts("assignment", "Canvas Assignment ID").withRequiredArg().ofType(Integer.class).required();
        Help = Parser.accepts("help", "Print this help message").forHelp();
    }

    private static TestResult parsePoints(File f, TestResult tr, final String nonce) throws IOException {
        final Pattern patEarned = Pattern.compile("<scoreActual(\\d*)>\\s*(\\d+)</scoreActual>");
        final Pattern patPossible = Pattern.compile("<scorePossible(\\d*)>\\s*(\\d+)</scorePossible>");

        Double pointsEarned = null, pointsPossible = null;
        boolean flagged = false;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = patEarned.matcher(line);
                if (m.find()) {
                    String n = m.group(1);
                    if (n.equals(nonce)) {
                        pointsEarned = Double.parseDouble(m.group(2));
                    } else {
                        LOG.severe("Expecting nonce "+nonce+" but found: "+line);
                        flagged = true;
                        break;
                    }
                }
                m = patPossible.matcher(line);
                if (m.find()) {
                    String n = m.group(1);
                    if (n.equals(nonce)) {
                        pointsPossible = Double.parseDouble(m.group(2));
                    } else {
                        LOG.severe("Expecting nonce "+nonce+" but found: "+line);
                        flagged = true;
                        break;
                    }
                }
            }
        }

        // strip nonce from f via sed
        ProcessBuilder pb = new ProcessBuilder("sed", "--in-place=", "-e", "s/"+nonce+"//", f.toString());
        try {
            Common.check_call(pb);
        } catch (InterruptedException e) {
            LOG.warning(Common.t2s(e,"running sed to erase nonce"));
        }

        if (flagged) {
            pointsEarned = 0.0;
            pointsPossible = 1.0;
        }

        TestResult newTR = new TestResult(pointsEarned, pointsPossible, tr.stdoutFile, tr.stderrFile);
        newTR.flagged = flagged;
        return newTR;
    }

    public static void main(String[] args) {
        Options = Parser.parse(args);

        if (Options.has(Help)) {
            try {
                Parser.printHelpOn(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        final Path SUBM_DIR = Options.valueOf(SubmissionDir).toPath();
        assert SUBM_DIR.toFile().isDirectory() : SUBM_DIR.toString();

        // log all messages to disk
        LOG.setLevel(Level.ALL);
        try {
            FileHandler fh = new FileHandler(SUBM_DIR.resolve("GradeWorker.log").toString(), true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.ALL);
            LOG.addHandler(fh); // NB: we also log to the console
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // clone git repo
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "git clone "+REPO_URL+" "+REPO_WC_NAME);
        pb.directory(SUBM_DIR.toFile());
        try {
            Common.check_call(pb);
        } catch (Exception e) {
            LOG.severe(Common.t2s(e,"failed running git clone"));
            System.exit(2);
        }

        Optional<Lab> thisLabMaybe = Arrays.stream(GradeCoordinator.LABS).filter(l -> l.canvasAssnId == Options.valueOf(AssignmentID)).findFirst();
        assert thisLabMaybe.isPresent();
        final Lab THE_LAB = thisLabMaybe.get();

        // delete all the directories we don't need, e.g., .git/ and other labs
        try {
            FileUtils.deleteDirectory(SUBM_DIR.resolve(REPO_WC_NAME).resolve(".git").toFile());
            SUBM_DIR.resolve(REPO_WC_NAME).resolve("common/pennsim/PennSim.jar").toFile().delete();
            // delete all working copy dirs except for common/ and this lab
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(SUBM_DIR.resolve(REPO_WC_NAME))) {
                for (Path path: stream) {
                    if (!path.endsWith("common") && !path.endsWith(THE_LAB.repoLeafDir)) {
                        FileUtils.deleteQuietly(path.toFile());
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            LOG.warning(Common.t2s(e,"couldn't delete unused directories from git working copy"));
        }
        // TODO: replace test_data with a symlink to a single, shared copy

        final TestResult tr;
        if (THE_LAB.fileToSubmit.toString().endsWith(".zip")) {
            if (THE_LAB.shortName.endsWith("full")) {
                tr = runTest(THE_LAB, "'export TEST_CASE=wireframe; make test'", SUBM_DIR);
            } else { // ALU-only lab
                tr = runTest(THE_LAB, "'export TEST_CASE=test_alu; make test'", SUBM_DIR);
                //tr = runTest(THE_LAB, "TEST_CASE=test_alu make test", SUBM_DIR);
            }
        } else { // non-zip-file lab
            tr = runTest(THE_LAB, "'make test'", SUBM_DIR);
        }

        try {
            String json = GsonFactory.getDefaultInstance().toPrettyString(tr);
            System.out.println(json);
            LOG.info("JSON response: " + json);
        } catch (IOException e) {
            LOG.severe(Common.t2s(e,"Converting TestResult to json"));
        }

    }

    /**
     * Run tests on a given submission, returning the score it earned
     * @param lab the Lab object for the assignment being graded
     * @param testCmd the shell command that invokes the tests
     * @param submDir the submission directory
     * @return The fields of this TestResult instance may be A) all null, B) null==scores only or C) all non-null.
     * A) indicates an error early setting up the test execution, B) an error during test execution, and C) successful
     * test completion.
     */
    private static TestResult runTest(Lab lab, String testCmd, Path submDir) {
        Path repoLabDir = submDir.resolve(REPO_WC_NAME).resolve(lab.repoLeafDir);
        assert repoLabDir.toFile().isDirectory() : repoLabDir.toString();

        // didn't make it far enough to get logs
        final TestResult emptyTR = new TestResult(null, null, null, null);

        // NB: submitted file is in the root of submDir/
        try {
            if (null == lab.archiveContents) { // students submit a single file
                // delete skeleton code
                Files.delete(repoLabDir.resolve(lab.fileToSubmit));

                // copy it to the repoLabDir where it needs to be
                Files.copy(Options.valueOf(SubmittedFile).toPath(), repoLabDir.resolve(lab.fileToSubmit));

            } else { // students submit a .zip archive
                // delete skeleton code
                for (String s : lab.archiveContents) {
                    try {
                        Files.delete(repoLabDir.resolve(s));
                    } catch (NoSuchFileException e) {}
                }

                // copy .zip to repoLabDir, and extract it
                Files.copy(Options.valueOf(SubmittedFile).toPath(), repoLabDir.resolve(lab.fileToSubmit));
                ProcessBuilder pb = new ProcessBuilder("unzip", lab.fileToSubmit.toString());
                pb.directory(repoLabDir.toFile());
                Common.check_call(pb);
            }
        } catch (Exception e) {
            LOG.severe(Common.t2s(e,"error moving submitted file(s) into place"));
            return emptyTR;
        }

        // setup nonce to authenticate the score printout
        final String NONCE = String.valueOf(Math.round(Math.random() * Long.MAX_VALUE));
        File print_points = repoLabDir.resolve("print_points.v").toFile();
        boolean deleted = print_points.delete();
        assert deleted;
        try (PrintWriter pp = new PrintWriter(print_points)) {
            pp.println("task printPoints;");
            pp.println("  input [31:0] possible, actual;");
            pp.println("  begin");
            pp.println("    $display(\"<scorePossible"+NONCE+">%d</scorePossible>\", possible);");
            pp.println("    $display(\"<scoreActual"+NONCE+">%d</scoreActual>\", actual);");
            pp.println("  end");
            pp.println("endtask");
        } catch (FileNotFoundException e) {
            LOG.severe(Common.t2s(e,"couldn't open "+print_points.toString()));
        }

        // launch test
        File outF = new File(Options.valueOf(SubmissionDir), "sim-out.txt");
        File errF = new File(Options.valueOf(SubmissionDir), "sim-err.txt");

        // we got some logs, but no scores
        final TestResult logsTR = new TestResult(null, null,
                outF.getAbsolutePath(), errF.getAbsolutePath());

        String pbCmd = "source /home1/c/cis371/software/Vivado/2017.4/settings64.sh && python /home1/c/cis501/bin/python-timeout/TimedSubprocess.py 10m " + testCmd;
        LOG.info(pbCmd);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", pbCmd);
        pb.directory(repoLabDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.to(errF));
        pb.redirectOutput(ProcessBuilder.Redirect.to(outF));
        //pb.inheritIO();
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            LOG.severe(Common.t2s(e, "error launching test"));
            return emptyTR;
        }

        // wait for test to complete
        try {
            int exitCode = p.waitFor();
            // TODO: take the last 1K suffix of stdout
            if (0 != exitCode) {
                LOG.severe("test process exited uncleanly with code: " + exitCode);
                return logsTR;
            }
        } catch (InterruptedException e) {
            LOG.severe(Common.t2s(e,"interrupted while waiting for test/StreamConsumers"));
            return logsTR;
        }

        // extract score from test output
        try {
            return parsePoints(outF, logsTR, NONCE);
        } catch (IOException e) {
            LOG.severe(Common.t2s(e,"error with parsePoints()"));
            return logsTR;
        }
    }

}