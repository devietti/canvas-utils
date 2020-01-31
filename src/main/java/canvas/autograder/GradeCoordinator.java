package canvas.autograder;

import canvas.Common;
import canvas.apiobjects.*;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.Key;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import javax.annotation.Nullable;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static canvas.autograder.SocketMessage.*;
import static java.time.temporal.ChronoUnit.SECONDS;

/** High-priority errors get emailed to Joe */
class EmailHandler extends Handler {
    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < this.getLevel().intValue()) return;

        String s = DateTimeFormatter.ISO_INSTANT.format(Instant.now()) +
                " - " +
                record.getSourceClassName() +
                "#" +
                record.getSourceMethodName() +
                " - " +
                record.getMessage();

        // send via email
        GradeCoordinator.sendEmail(Collections.singletonList("devietti@cis.upenn.edu"),
                "[CIS 501 autograder] error", s, null);
    }

    @Override
    public void flush() {}
    @Override
    public void close() throws SecurityException {}
}

public class GradeCoordinator {

    /* CONFIGURATION VARIABLES */

    private static Path AG_OUTPUT_ROOT = FileSystems.getDefault().getPath("/home1/c/cis501/autograder-output");
    //private static Path AG_OUTPUT_ROOT = FileSystems.getDefault().getPath("/Users/devietti/Classes/cis501-perennial/CanvasUtils/tmp/autograder-output");
    private final static int UPLOAD_FILE_SIZE_LIMIT = 5 * 1024 * 1024;
    private final static String UPLOAD_FILE_SIZE_LIMIT_STR = "5MB";
    /**
     * Max output file size that we will try to email. Currently, 1MB
     */
    private static final int MAX_EMAIL_FILE_SIZE = 1 << 20;
    /**
     * Penalty for a late submission: it gets 75% of its original score.
     */
    public static final double LATE_PENALTY = 0.75;
    /**
     * Submissions a bit after the deadline don't incur the late penalty
     */
    public static final Duration GRACE_PERIOD = Duration.ofMinutes(15);
    /** Submissions this far after the deadline are not accepted. */
    public static final Duration LOCK_PERIOD = Duration.ofHours(48);

    final public static Lab[] LABS = new Lab[] {
            new Lab(7513535, "lab1", Paths.get("rca.zip"), strs("rca.v","output/rca4.bit"), Paths.get("lab1")),

            new Lab(7651954,  "lab2div", Paths.get("lc4_divider.v"), null, Paths.get("lab2-div")),
            new Lab(7513537,  "lab2alu", Paths.get("alu.zip"),
                    strs("lc4_divider.v", "lc4_cla.v", "lc4_alu.v", "output/alu.bit"), Paths.get("lab2-alu")),
            new Lab(7513540,  "lab2gpn", Paths.get("lc4_cla.v"), null, Paths.get("lab2-alu")),

            new Lab(7513543, "lab3alu", Paths.get("single.zip"),
                    strs("lc4_regfile.v","lc4_single.v","lc4_divider.v","lc4_cla.v","lc4_alu.v","output/singlecycle.bit"),
                    Paths.get("lab3-singlecycle")),
            new Lab(7513544, "lab3full", Paths.get("single.zip"),
                    strs("lc4_regfile.v","lc4_single.v","lc4_divider.v","lc4_cla.v","lc4_alu.v","output/singlecycle.bit"),
                    Paths.get("lab3-singlecycle")),

            new Lab(7513547,  "lab4alu", Paths.get("pipeline.zip"),
                    strs("lc4_regfile.v", "lc4_pipeline.v", "lc4_divider.v","lc4_cla.v","lc4_alu.v","output/pipeline.bit"),
                    Paths.get("lab4-pipeline")),
            new Lab(7513548,  "lab4full", Paths.get("pipeline.zip"),
                    strs("lc4_regfile.v", "lc4_pipeline.v", "lc4_divider.v","lc4_cla.v","lc4_alu.v","output/pipeline.bit"),
                    Paths.get("lab4-pipeline")),

            new Lab(7513549,  "lab5alu", Paths.get("superscalar.zip"),
                    strs("lc4_regfile_ss.v", "lc4_superscalar.v", "lc4_divider.v","lc4_cla.v","lc4_alu.v","output/superscalar.bit"),
                    Paths.get("lab5-superscalar")),
            new Lab(7513550,  "lab5full", Paths.get("superscalar.zip"),
                    strs("lc4_regfile_ss.v", "lc4_superscalar.v", "lc4_divider.v","lc4_cla.v","lc4_alu.v","output/superscalar.bit"),
                    Paths.get("lab5-superscalar"))
    };
    private static Set<String> strs(String... strs) {
        return new HashSet<>(Arrays.asList(strs));
    }

    private static final OptionSpec Help;
    private static final OptionSpec TestMode;
    private static final OptionSpec QuietMode;
    private static final OptionSpec<Integer> Port;
    private static final OptionParser Parser;
    private static OptionSet Options;
    static {
        Parser = new OptionParser();
        TestMode = Parser.accepts("test-mode", "Grade submissions from the sandbox site instead");
        QuietMode = Parser.accepts("quiet-mode", "Don't send emails to students");
        Port = Parser.accepts("port", "listen for commands on this localhost port. Commands are a single character. Available commands: r(un) and q(uit)").withRequiredArg().ofType(Integer.class);
        Help = Parser.accepts("help", "Print this help message").forHelp();
    }

    private final static Logger LOG = Logger.getLogger("GradeCoordinator");
    private final static CloseableHttpClient myHttpClient = HttpClients.createDefault();
    private final static ExecutorService exe = Executors.newCachedThreadPool();
    private static boolean parallelGrading = true;
    private final static List<FutureTask<Boolean>> myFutures = new LinkedList<>();

    public static void main(String[] args) throws IOException {

        Options = Parser.parse(args);
        if (Options.has(Help)) {
            try {
                Parser.printHelpOn(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        if (Options.has(TestMode)) {
            Common.useSandboxSite();
            parallelGrading = false;
            AG_OUTPUT_ROOT = FileSystems.getDefault().getPath("/home1/c/cis501/autograder-output-test/");
        }

        if (!AG_OUTPUT_ROOT.toFile().isDirectory()) {
            AG_OUTPUT_ROOT.toFile().mkdirs();
        }

        // log everything to the console
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        LOG.addHandler(ch);
        // log everything to disk
        LOG.setLevel(Level.INFO);
        final FileHandler fh = new FileHandler(AG_OUTPUT_ROOT.resolve("GradeCoordinator.log").toString(), true);
        fh.setLevel(Level.INFO);
        fh.setFormatter(new SimpleFormatter());
        LOG.addHandler(fh);
        // send email for severe messages
        final EmailHandler mh = new EmailHandler();
        mh.setLevel(Level.SEVERE);
        LOG.addHandler(mh);

        Common.setup();

        // pull emails for each User (check their Profile if necessary)
        Map<Integer,User> userOfId = new HashMap<>();
        GenericUrl url = new GenericUrl(Common.CourseURL() + "users");
        url.put("enrollment_type[]","student");
        url.put("enrollment_state[]", "active");
        List<User> users = Common.getAsList(url, User[].class);
        for (User u : users) {
            if (null == u.email) {
                // NB: have to get student's email via their Profile
                // test students have User.email, but no Profile.primary_email
                Profile prof = Common.getAs(new GenericUrl(Common.BASE_URL + "users/" + u.id + "/profile"), Profile.class);
                LOG.finest("email (Profile/User): " + prof.primary_email + " / " + u.email);
                u.email = prof.primary_email;
                if (null == u.email) {
                    LOG.warning(String.format("Student %s has no valid email address, setting to ''", u.name));
                    u.email = "";
                }
            }
            if (Options.has(TestMode) && u.email.startsWith("g02") && u.email.endsWith("@seas.upenn.edu")) {
                // in test mode, replace all fake students' emails with Joe's
                u.email = "devietti@cis.upenn.edu";
            }

            userOfId.put(u.id, u);
        }


        // trying to gather Submissions from unpublished Assignments causes Canvas to return error responses, so we
        // filter out the unpublished Assignments here
        final List<String> assignmentsToGrade = Arrays.stream(LABS)
                .filter(l -> {
                    try {
                        return Common.getAs("assignments/" + l.canvasAssnId, Assignment.class).published;
                    } catch (IOException e) { return false; }
                })
                .map(l -> String.valueOf(l.canvasAssnId)).collect(Collectors.toList());
        try (ServerSocket serverSocket = new ServerSocket(Options.valueOf(Port), 1, InetAddress.getByName("127.0.0.1"))) {
            Socket sock = null;
            if (Options.has(TestMode)) { // test suite uses a long-lived socket connection
                serverSocket.setSoTimeout(120 * 1000); // 2 minutes
                sock = serverSocket.accept();
            } else {
                serverSocket.setSoTimeout(5 * 1000); // 5 seconds
            }
            while (true) {
                OutputStream sockOut = null;

                // listen for commands via a socket
                LOG.finest("listening for command via socket");
                try {
                    if (!Options.has(TestMode)) {
                        // in production mode, we expect the socket to timeout often
                        sock = serverSocket.accept();
                    }
                    sockOut = sock.getOutputStream();
                    int i = sock.getInputStream().read();
                    if (-1 == i) { // end-of-stream
                        continue;
                    }
                    final char c = (char) i;
                    if (Nop.ch == c) {
                        LOG.finest("nop command received via socket");
                        continue;
                    } else if (Version.ch == c) {
                        LOG.info("Canvas autograder version: "+Common.VERSION);
                        continue;
                    } else if (Run.ch == c) {
                        LOG.info("run command received via socket");
                    } else if (ParallelGrading.ch == c) {
                        LOG.info("parallel grading command received via socket");
                        parallelGrading = true;
                        continue;
                    } else if (SerialGrading.ch == c) {
                        LOG.info("serial grading command received via socket");
                        parallelGrading = false;
                        continue;
                    } else if (Quit.ch == c) {
                        LOG.info("quit command received via socket. Waiting on futures first...");
                        waitOnFutures(sockOut);
                        LOG.info("Done waiting for futures, quitting...");
                        System.exit(0);
                    } else {
                        LOG.warning(String.format("Unknown message on socket: '%c' (%d). Ignoring.", c, i));
                        continue;
                    }
                } catch (SocketTimeoutException t) {
                    LOG.finest("socket listen timed out, running autograder...");
                }

                url = new GenericUrl(Common.CourseURL() + "students/submissions");
                url.put("student_ids[]", "all");
                url.put("include[]", "group"); // to get group info
                if (!Options.has(TestMode)) { // in test mode, get submissions to all assignments
                    url.put("assignment_ids[]", assignmentsToGrade);
                }
                url.put("workflow_state", "submitted");
                LOG.finest(url.build());

                List<Submission> submissions = Common.getAsList(url, Submission[].class);
                LOG.finest("found " + submissions.size() + " submissions");

                // find set of Assignments that were submitted
                Set<Integer> assnIDs = submissions.stream().map(s -> s.assignment_id).collect(Collectors.toSet());
                for (final Integer asnID : assnIDs) {

                    // build map, for this Assignment, of GroupID => List<Submission>. For a solo assignment, lists
                    // will all be singletons NB: use negative of user ID as fake group ID
                    Map<Integer, List<Submission>> subsOfGroupID = submissions.stream()
                            .filter(s -> asnID == s.assignment_id)
                            .collect(Collectors.groupingBy(s -> (null == s.group.id || 0 == s.group.id) ? -s.user_id : s.group.id));
                    LOG.finest(subsOfGroupID.toString());
                    for (List<Submission> subs : subsOfGroupID.values()) {
                        LOG.finest(subs.size() + " submissions for groupID " + subs.get(0).group.id);
                        LOG.finest("submission: " + subs.get(0).toString());
                        assert subs.stream().allMatch(s -> userOfId.containsKey(s.user_id));
                        List<User> groupMembers = subs.stream().map(s -> userOfId.get(s.user_id)).collect(Collectors.toList());
                        if (!gradeSubmission(groupMembers, subs.get(0))) {
                            LOG.warning("problem grading submission " + subs.get(0).toString());
                            // write character to socket to notify that submission is graded
                            socketWrite(sockOut, SocketMessage.ProblemGrading);
                        } else if (!parallelGrading) {
                            socketWrite(sockOut, SocketMessage.GradedSuccessfully);
                        }
                    }
                }

                waitOnFutures(sockOut);

                // in production mode, run once and then exit. Rely on cronjob to poll for new submissions.
                if (!Options.has(TestMode)) {
                    return;
                }

            } // end main loop
        } // end ServerSocker try

    } // end main()

    private static void waitOnFutures(OutputStream sockOut) {
        List<FutureTask<Boolean>> futuresToRemove = new LinkedList<>();
        for (FutureTask<Boolean> f : myFutures) {
            if (f.isDone() || Options.has(TestMode)) {
                try {
                    futuresToRemove.add(f);
                    if (!f.get()) {
                        // TODO: have future provide useful info via .toString() so we can call it here
                        LOG.severe("Future task did not run ok!");
                        socketWrite(sockOut, SocketMessage.ProblemGrading);
                    } else {
                        socketWrite(sockOut, SocketMessage.GradedSuccessfully);
                    }
                } catch (Exception e) {
                    LOG.severe(Common.t2s(e, "future crashed"));
                }
            }
        }
        myFutures.removeAll(futuresToRemove); // remove done futures
    }

    /**
     * @param subUsers the User(s) (>1 for group assignments) on whose behalf this submission occurred
     * @param sub the Submission
     * @return true if the assignment was graded successfully, false if an error occurred */
    private static boolean gradeSubmission(List<User> subUsers, Submission sub) {
        // find corresponding Canvas Assignment object
        final Assignment THE_ASSN;
        try {
            THE_ASSN = Common.getAs("assignments/" + sub.assignment_id, Assignment.class);
        } catch (IOException e) {
            LOG.severe(Common.t2s(e));
            return false;
        }

        // find the Lab object for this submission
        final Optional<Lab> thisLabMaybe;
        if (Options.has(TestMode)) {
            thisLabMaybe = Arrays.stream(LABS).filter(l -> THE_ASSN.name.equals(l.shortName)).findFirst();
        } else {
            thisLabMaybe = Arrays.stream(LABS).filter(l -> l.canvasAssnId == sub.assignment_id).findFirst();
        }
        assert thisLabMaybe.isPresent();
        final Lab THE_LAB = thisLabMaybe.get();

        sub.parseTimes();
        final String subTime = Common.canvasDateFormat(sub.submitted_at);
        final String subject = String.format("%s submission on %s", THE_LAB.shortName, subTime);

        LOG.info(String.format("** Starting submission assignment:%s user:%d group:%s attempt:%d time:%s",
                THE_ASSN.name, sub.user_id, subUsers.toString(), sub.attempt, subTime));

        // check that attachment is well-formed

        if (sub.attachments.length != 1) {
            String msg = String.format("%d files were submitted, instead of just one (%s) as expected.",
                    sub.attachments.length, THE_LAB.fileToSubmit);
            sendMessage(subUsers, sub.assignment_id, 0.0, subject, msg, null);
            return false;
        }

        final Attachment att = sub.attachments[0];
        if (att.size > UPLOAD_FILE_SIZE_LIMIT) {
            String msg = String.format("submitted file %s exceeds the %s file size limit.",
                    att.display_name, UPLOAD_FILE_SIZE_LIMIT_STR);
            sendMessage(subUsers, sub.assignment_id, 0.0, subject, msg, null);
            return false;
        }

        final Path LAB_ROOT = AG_OUTPUT_ROOT.resolve(THE_LAB.shortName);

        // create unique folder to hold this submission
        String pennkeys = subUsers.stream().map(u -> u.login_id).collect(Collectors.joining("-"));

        String subTimeLong = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("America/New_York")).format(sub.submitted_at);
        String submissionDirName = String.format("%s_%d_%s", pennkeys, sub.attempt, subTimeLong);
        final Path SUBM_DIR = LAB_ROOT.resolve(submissionDirName);
        if (SUBM_DIR.toFile().isDirectory()) {
            // suppress this log message as it fills up the logs with garbage
            //LOG.info(submissionDirName+" already exists, skipping");
            return true; // don't repeatedly try to grade the same submission
        }
        SUBM_DIR.toFile().mkdirs();

        // write out submitted file
        Path submittedFile = SUBM_DIR.resolve(THE_LAB.fileToSubmit);
        HttpGet httpget = new HttpGet(att.url);
        LOG.info(String.format("submitted file %s of type %s has size %dB",
                THE_LAB.fileToSubmit, sub.attachments[0].content_type, sub.attachments[0].size));
        try (FileOutputStream fos = new FileOutputStream(submittedFile.toFile()); CloseableHttpResponse r2 = myHttpClient.execute(httpget)) {
            HttpEntity entity = r2.getEntity();
            assert null != entity;
            entity.writeTo(fos);
        } catch (Exception e) {
            LOG.severe(Common.t2s(e));
            sendMessage(subUsers, sub.assignment_id, null, subject,
                    "autograder encountered an internal error. This submission was not graded.", null);
            return false;
        }

        // check submitted file contents
        if (null != THE_LAB.archiveContents) {
            try (final ZipFile f = new ZipFile(submittedFile.toFile())) {
                Set<String> actual = Collections.list(f.entries()).stream().map(ZipEntry::getName).collect(Collectors.toSet());
                Set<String> expected = new HashSet<>(THE_LAB.archiveContents);

                actual.removeIf(n -> n.equals(".DS_Store"));
                if (!actual.equals(expected)) {
                    if (actual.containsAll(expected)) { // extra file(s)
                        actual.removeAll(expected);
                        String err = String.format("%s should not contain extraneous files '%s'.",
                                submittedFile.getFileName().toString(), String.join(",", actual));
                        sendMessage(subUsers, sub.assignment_id, 0.0, subject, err, null);
                        return false;
                    } else { // missing file(s)
                        expected.removeAll(actual);
                        String err = String.format("%s is missing required file(s) '%s'.",
                                submittedFile.getFileName().toString(), String.join(",", expected));
                        sendMessage(subUsers, sub.assignment_id, 0.0, subject, err, null);
                        return false;
                    }
                }
            } catch (IOException e) {
                LOG.warning(Common.t2s(e,"malformed zip archive?"));
                sendMessage(subUsers, sub.assignment_id, 0.0, subject,
                        String.format("zip archive %s appears corrupted, please submit again.",
                                submittedFile.getFileName().toString()), null);
                return false;
            }
        }

        sendMessage(subUsers, sub.assignment_id, null,
                subject,"grading submission...", null);

        // invoke autograder on random biglab node via a FutureTask
        // avoid big02 which is borked, big03 where the GradeCoordinator runs
        List<Integer> biglabNodes = Arrays.asList(1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
        Collections.shuffle(biglabNodes);
        String nodeName = String.format("cis501@big%02d.seas.upenn.edu", biglabNodes.get(0));
        FutureTask<Boolean> runGradeWorker = new FutureTask<>(() -> {
            ProcessBuilder pb = new ProcessBuilder("ssh", "-o", "StrictHostKeyChecking=no", nodeName,
                    "java", "-enableassertions", "-cp", "CanvasUtils-2.0.0-jar-with-dependencies.jar",
                    "canvas.autograder.GradeWorker", "--submitted-file", submittedFile.toString(),
                    "--submission-dir", SUBM_DIR.toString(),
                    "--assignment", String.valueOf(Options.has(TestMode) ? THE_LAB.canvasAssnId : sub.assignment_id));
            LOG.info("running command: " + pb.command().toString());

            final Process p;
            final TestResult tr;

            try {
                p = pb.start();
                int status = p.waitFor();
                String stdout = IOUtils.toString(p.getInputStream(), Charset.defaultCharset());
                String stderr = IOUtils.toString(p.getErrorStream(), Charset.defaultCharset());
                String result = String.format("status: %d%n stderr: %s%n stdout: %s%n", status, stdout, stderr);

                if (0 != status) {
                    LOG.severe(result);
                    return false;
                }

                tr = GsonFactory.getDefaultInstance().createJsonParser(stdout).parse(TestResult.class);

            } catch (Exception e) {
                LOG.severe(Common.t2s(e,"ssh failure"));
                return false;
            }

            if (null != tr.pointsEarned) { // GradeWorker ran to completion
                assert null != tr.pointsPossible && null != tr.stderrFile && null != tr.stdoutFile;

                File testStderr = new File(tr.stderrFile);
                File testStdout = new File(tr.stdoutFile);
                if (!testStderr.canRead()) {
                    LOG.severe("can't read test's stderr file: "+testStderr.toString());
                }
                if (!testStdout.canRead()) {
                    LOG.severe("can't read test's stdout file: "+testStdout.toString());
                }

                String gradingMsg = "";

                // threshold grading
                final double threshold = 0.9 * tr.pointsPossible;
                if (tr.pointsEarned < tr.pointsPossible && tr.pointsEarned > threshold) {
                    double cappedPoints = Math.min(tr.pointsEarned, threshold);
                    gradingMsg += String.format("Non-fully correct solution capped at %.1f points (original score: %.1f)%n",
                            cappedPoints, tr.pointsEarned);
                    tr.pointsEarned = cappedPoints;
                }

                // apply late penalty as necessary
                if (sub.seconds_late > 0) {
                    Duration lateness = Duration.of(sub.seconds_late, SECONDS);
                    if (lateness.compareTo(GRACE_PERIOD) > 0) {
                        double origPoints = tr.pointsEarned;
                        tr.pointsEarned *= LATE_PENALTY;
                        gradingMsg += String.format("%.1f points lost due to late submission.", origPoints-tr.pointsEarned);

                        if (lateness.compareTo(LOCK_PERIOD.plus(GRACE_PERIOD)) > 0) {
                            LOG.severe("SUPER late submission for "+THE_LAB.shortName+" from "+sub.user_id+". Assignment should have been locked!");
                            tr.pointsEarned = 0.0;
                            gradingMsg += "Assignment submitted beyond the late period, no credit given.";
                        }
                    }
                }

                if (tr.flagged) {
                    String msg = String.format("FLAGGED submission of %s by %d at %s.",
                            THE_LAB.shortName, sub.user_id, subTime);
                    LOG.severe(msg);
                }

                // compute score to nearest 0.1
                double fracEarned = tr.pointsEarned / tr.pointsPossible;
                double percEarned = Math.round(fracEarned * 1000.0) / 10.0;
                double canvasPtsEarned = Math.round(fracEarned * THE_ASSN.points_possible * 10.0) / 10.0;

                String body = String.format("%.1f / %.0f tests passed = %.1f%% (%.1f points on Canvas) %n%s%nSee attached logs for more details.",
                        tr.pointsEarned, tr.pointsPossible, percEarned, canvasPtsEarned, gradingMsg);

                sendMessage(subUsers, sub.assignment_id, canvasPtsEarned,
                        subject, body, new File[]{testStderr,testStdout});

                return true;

            } else { // some kind of failure running the tests
                assert null == tr.pointsPossible;

                if (null != tr.stderrFile) {
                    File testStderr = new File(tr.stderrFile);
                    File testStdout = new File(tr.stdoutFile);

                    try {
                        LOG.severe("autograder/vivado failure, got stdout+stderr files. " + stringFromFile(testStderr, "stderr") + tailOfFile(1024, testStdout, "stdout"));
                        sendMessage(subUsers, sub.assignment_id, null,
                                subject, "Problem running Vivado", new File[]{testStderr,testStdout});
                    } catch (IOException e) {
                        LOG.severe("autograder failure, got stdout+stderr files. Error reading stderr file: " + Common.t2s(e));
                        sendMessage(subUsers, sub.assignment_id, null,
                                subject, "Autograder encountered an internal error", new File[]{testStderr,testStdout});
                    }

                } else {
                    LOG.severe("autograder failure, NO stdout+stderr files");
                    sendMessage(subUsers, sub.assignment_id, null,
                            subject, "Autograder encountered an internal error", null);
                }

                return false;
            }
        });

        if (parallelGrading) {
            myFutures.add(runGradeWorker);
            exe.execute(runGradeWorker);
        } else { // run futures synchronously
            exe.execute(runGradeWorker);
            try {
                return runGradeWorker.get();
            } catch (Exception e) {
                LOG.severe(Common.t2s(e, "future crashed"));
            }
        }

        return true;
    }

    /** returns the last length bytes from f */
    private static String tailOfFile(int length, File f, String tag) throws IOException {
        long fileSize = f.length();
        try (FileReader fr = new FileReader(f)) {

            if (length > fileSize) {
                length = (int) fileSize;
            }
            final char[] cbuf = new char[length];
            final long skipRequest = fileSize - length;
            final long skipActual = fr.skip(skipRequest);
            if (skipActual != skipRequest) {
                LOG.warning(String.format("wanted to skip %d bytes but only skipped %d %n", skipRequest, skipActual));
            }
            int charsRead = fr.read(cbuf);
            if (charsRead != length) {
                LOG.warning(String.format("wanted to read %d bytes but only read %d %n", length, charsRead));
            }
            String cbufS = new String(cbuf, 0, charsRead);
            return String.format("%s:%n%s%n", tag, cbufS);
        }
    }

    private static String stringFromFile(File f, String tag) throws IOException {
        final char[] cbuf = new char[MAX_EMAIL_FILE_SIZE];
        final String trunc = "[NOTE] output truncated, exceeds email limit";

        try (FileReader fr = new FileReader(f)) {
            int charsRead = fr.read(cbuf);
            if (charsRead > 0) {
                String cbufS = new String(cbuf, 0, charsRead);
                if (charsRead == MAX_EMAIL_FILE_SIZE) {
                    cbufS += "\n...";
                }
                fr.close();
                return String.format("%s:%n%s%n%s%n",
                        tag, cbufS, (charsRead == MAX_EMAIL_FILE_SIZE ? trunc : ""));
            }
            return "";
        }
    }

    static class SCComment {
        @Key
        String text_comment;
        @Key
        boolean group_comment = true;
        @Key
        Integer[] file_ids;
    }
    static class SCSubmission {
        @Key
        double posted_grade;
    }
    static class UploadGradeComment {
        @Key
        SCComment comment = new SCComment();
        @Key
        SCSubmission submission = null;
    }

    /**
     * Sends the given message via 1) a Submission Comment and 2) an email
     * @param recipients list of Canvas Users to whom this message will be sent
     * @param assnId Canvas Assignment ID
     * @param score if non-null, the latest Submission will have this grade. Otherwise, no grade is set
     *              (any previous grade remains).
     * @param subject prepended to Submission Comment, used as email subject
     * @param message the message to send
     * @param attachments array of Files to attach to the message
     */
    private static void sendMessage(List<User> recipients, int assnId, Double score,
                                    String subject, String message, File[] attachments) {
        LOG.info(String.format("sendMessage() to %s: %.2f %s %s",
                recipients.stream().map(r -> r.login_id).collect(Collectors.joining(",")),
                null == score ? -1.0 : score, subject, message));

        // send message via Submission Comment

        GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + assnId + "/submissions/" + recipients.get(0).id);
        UploadGradeComment ugc = new UploadGradeComment();
        ugc.comment.text_comment = subject+": "+message;
        ugc.comment.group_comment = true;
        if (null != score) {
            ugc.submission = new SCSubmission();
            ugc.submission.posted_grade = score;
        }
        if (null != attachments) { // upload each file to Canvas, to we can attach to the SubmissionComment
            GenericUrl fileUrl = new GenericUrl(Common.CourseURL() + "assignments/" + assnId + "/submissions/"+ recipients.get(0).id+"/comments/files");
            LOG.finer(fileUrl.toString());
            List<Integer> fileIDs = new LinkedList<>();
            for (File f : Arrays.stream(attachments).filter(f -> f.length() > 0).collect(Collectors.toList())) {
                try {
                    CanvasFile cf = Common.uploadFile(fileUrl, f);
                    fileIDs.add(cf.id);
                } catch (Exception e) {
                    LOG.severe(Common.t2s(e,"couldn't upload file for SubmissionComment"));
                    return;
                }
            }
            ugc.comment.file_ids = fileIDs.toArray(new Integer[]{});
        }
        LOG.finer(url.toString());
        JsonHttpContent json = new JsonHttpContent(new GsonFactory(), ugc);

        HttpRequest request;
        try {
            request = Common.requestFactory.buildPutRequest(url, json);
            request.getHeaders().setAuthorization(Common.TOKEN);
            HttpResponse resp = request.execute();
            LOG.info("SubmissionComment PUT response: " + resp.parseAsString().substring(0,40)+"...");
        } catch (IOException e) {
            LOG.severe(Common.t2s(e,"sendMessage() Canvas SubmissionComment PUT error"));
        }

        if (!Options.has(QuietMode)) {
            // send email
            sendEmail(recipients.stream().map(r -> r.email).collect(Collectors.toList()),
                    "[CIS 501 autograder] " + subject, message, attachments);
        }
    }

    /**
     * Send an email to the specified recipients
     * @param recipients list of email addresses to send to
     * @param subject subject for email
     * @param body body of email
     * @param attachments attachments to send
     */
    static void sendEmail(List<String> recipients, String subject, String body, File[] attachments) {
        List<String> mailCmdParts = new LinkedList<>();
        mailCmdParts.add("mail");
        mailCmdParts.add("-s");
        mailCmdParts.add(subject);
        if (null != attachments) {
            for (File f : attachments) {
                if (f.length() > 0) {
                    mailCmdParts.add("-a");
                    mailCmdParts.add(f.toString());
                }
            }
        }
        mailCmdParts.addAll(recipients);
        ProcessBuilder pb = new ProcessBuilder(mailCmdParts);
        LOG.finest(mailCmdParts.toString());

        // NB: errors from sending email can't be Level.SEVERE, since that will trigger endless recursive email (attempts)

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            LOG.warning(Common.t2s(e,"error while launching mail command"));
            return;
        }
        PrintStream ps = new PrintStream(proc.getOutputStream());
        ps.print(body);
        ps.close();

        int rc;
        try {
            rc = proc.waitFor();
            if (0 != rc) {
                LOG.warning("mail command " + pb.command().toString() + " exited with code " + rc);
            }
        } catch (InterruptedException e) {
            LOG.warning(Common.t2s(e,"interrupted while waiting for mail command"));
        }
    }

    private static void socketWrite(@Nullable OutputStream os, SocketMessage sm) {
        if (null == os) return;

        String msg = "Trying to write "+sm.toString()+" to socket";
        try {
            LOG.finest(msg);
            os.write(sm.ch);
        } catch (IOException e) {
            LOG.finest(Common.t2s(e,msg));
        }
    }

}