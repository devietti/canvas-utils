package canvas;

import canvas.apiobjects.*;
import canvas.autograder.GradeCoordinator;
import canvas.autograder.GradeWorker;
import canvas.autograder.Lab;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.util.Key;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static canvas.AutograderTest.TestVariant.*;
import static canvas.Common.*;
import static canvas.autograder.SocketMessage.*;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.Assert.*;

// these classes are used for submitting Assignments via JSON POST request
class MakeSubmission {
    @Key
    Comment comment = new Comment();
    @Key
    Sub submission = new Sub();
}
class Comment {
    @Key
    String text_comment;
}
class Sub {
    @Key
    String submission_type = "online_upload";
    @Key
    int[] file_ids;
}

class AssignmentDueDate {
    @Key
    String due_at;
}

@RunWith(Enclosed.class)
public class AutograderTest {

    /** NB: hard-coded group category id (from sandbox course)! */
    private static final Integer GROUP_CAT_ID = 70211;
    private static final String AG_HOST = "cis501@big09.seas.upenn.edu";
    private static final String AG_SOCKET_PORT = "10000";
    private static final Path PROJECT_ROOT = Paths.get("/Users/devietti/Classes/perennial-comparch/CanvasUtils");
    private static final File RES_DIR = PROJECT_ROOT.resolve("src/test/resources").toFile();
    private static final Path GIT_WC_DIR = PROJECT_ROOT.resolve("git.tmp");
    private static final File CORRUPT_ZIP = new File(RES_DIR, "zeroes.zip");
    private static final File TINY_FILE = new File(RES_DIR,"tiny.v");
    private static final String JAR_NAME = "CanvasUtils-2.0.0-jar-with-dependencies.jar";

    private static Process sshAutograder = null;
    private static Process sshTunnel = null;
    private static class AsnLab {
        Assignment assn;
        Lab lab;
    }
    private static final Map<String,AsnLab> asnOfName = new HashMap<>();

    private static final boolean MEASURE_TIMING = false;
    private static InputStream fromAG;
    private static OutputStream toAG;

    @BeforeClass
    public static void setup() throws IOException, InterruptedException {

        // NB: takes about 20s to reset Assignments, 5s to submit an Assignment
        if (MEASURE_TIMING) {
            System.err.println(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        }

        assertTrue(CORRUPT_ZIP.canRead());
        assertTrue(TINY_FILE.canRead());

        // ensure that the same version of the code is running here and on the autograder
        System.out.println("* checking jar checksums...");
        ProcessOutput local = Common.check_output(new ProcessBuilder("shasum",
                PROJECT_ROOT.resolve("target").resolve(JAR_NAME).toString()));
        ProcessOutput remote = Common.check_output(new ProcessBuilder("ssh", AG_HOST,
                "shasum",JAR_NAME));
        String localChecksum = local.stdout.split(" ")[0];
        String remoteChecksum = remote.stdout.split(" ")[0];
        if (!localChecksum.equals(remoteChecksum)) {
            System.err.format("UH-OH: checksums do not match! local:%s, remote:%s%n", localChecksum, remoteChecksum);
        }

        Common.setup();

        // get real assignments
        final List<Assignment> RealAsns = Common.getAsList("assignments", Assignment[].class);
        Map<Integer,Assignment>  RealAsnOfId = new HashMap<>();
        for (Assignment ra : RealAsns) {
            RealAsnOfId.put(ra.id,ra);
        }

        Common.useSandboxSite();

        { // tear down previous autograder (if any)
            System.out.println("* tearing down previous autograder...");
            ProcessBuilder pb = new ProcessBuilder("ssh", AG_HOST, "killall java || true");
            check_call(pb);
        }

        // delete all existing assignments
        System.out.println("* deleting existing sandbox Assignments...");
        List<Assignment> SandboxAsns = Common.getAsList("assignments", Assignment[].class);
        for (Assignment asn : SandboxAsns) {
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id);
            HttpRequest request = requestFactory.buildDeleteRequest(url);
            request.getHeaders().setAuthorization(TOKEN);
            HttpResponse response = request.execute();
            assertEquals(200, response.getStatusCode());
        }

        // setup new Assignments, one for each

        // create labs
        System.out.println("* creating new sandbox Assignments...");
        for (Lab l : GradeCoordinator.LABS) {
            assert RealAsnOfId.containsKey(l.canvasAssnId);
            final Assignment realAsn = RealAsnOfId.get(l.canvasAssnId);
            //System.err.format("real aid:%d name:%s gcid:%d %n", realAsn.id, realAsn.name, realAsn.group_category_id);
            Assignment asn = new Assignment();
            asn.name = l.shortName;
            asn.published = true;
            asn.submission_types = new String[]{"online_upload"};
            asn.allowed_extensions = new String[]{"txt",FilenameUtils.getExtension(l.fileToSubmit.toString())};
            asn.group_category_id = realAsn.isGroupAssignment() ? GROUP_CAT_ID : null;
            asn.points_possible = realAsn.points_possible;
            ZonedDateTime due = ZonedDateTime.now(ZoneId.of("UTC")).plus(Duration.of(30, MINUTES));
            asn.due_at_string = DateTimeFormatter.ISO_INSTANT.format(due);
            asn.lock_at_string = "";
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments");
            HttpResponse response = Common.postJSON(url, asn, "assignment");
            assertEquals(201, response.getStatusCode());
            Assignment created = response.parseAs(Assignment.class);
            AsnLab alab = new AsnLab();
            alab.assn = created;
            alab.lab = l;
            asnOfName.put(created.name,alab);
        }

        // launch test-mode autograder
        System.out.println("* launching autograder...");
        ProcessBuilder pb = new ProcessBuilder("ssh",AG_HOST,
                "java", "-enableassertions", "-cp", JAR_NAME,
                "canvas.autograder.GradeCoordinator","--test-mode","--port",AG_SOCKET_PORT);
        File sshStderr = new File(RES_DIR, "autograder.stderr");
        sshStderr.delete();
        pb.redirectError(sshStderr);
        File sshStdout = new File(RES_DIR, "autograder.stdout");
        sshStdout.delete();
        pb.redirectOutput(sshStdout);
        sshAutograder = pb.start();

        // clone git repo locally, to ensure its files are submittable
        System.out.println("* cloning git repo...");
        FileUtils.deleteDirectory(GIT_WC_DIR.toFile());
        pb = new ProcessBuilder("bash","-c","git clone "+ GradeWorker.REPO_URL+" "+GIT_WC_DIR);
        check_call(pb);

        // connect to autograder via ssh tunnel
        System.out.println("* connecting to autograder...");
        String tunnel = String.format("ssh -L %s:127.0.0.1:%s %s -N", AG_SOCKET_PORT, AG_SOCKET_PORT, AG_HOST);
        pb = new ProcessBuilder("/bin/bash","-c",tunnel);
        pb.redirectOutput(new File(RES_DIR, "sshtunnel.stdout"));
        pb.redirectError(new File(RES_DIR, "sshtunnel.stderr"));
        sshTunnel = pb.start();
        Thread.sleep(2_000); // wait for tunnel to establish
        Socket agSocket = new Socket("127.0.0.1", Integer.parseInt(AG_SOCKET_PORT));
        fromAG = agSocket.getInputStream();
        toAG = agSocket.getOutputStream();

        if (MEASURE_TIMING) {
            System.err.println(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        }

        System.out.println("* done with setup");
    }

    @AfterClass
    public static void teardown() throws IOException, InterruptedException {
        // shut down autograder
        toAG.write(Quit.ch);

        boolean exited = sshAutograder.waitFor(200, TimeUnit.MILLISECONDS);
        if (!exited) {
            sshAutograder.destroyForcibly();
        }

        sshTunnel.destroyForcibly();
    }

    enum TestVariant {
        // NB: can't test FileTooLarge because we have no way of deleting files used in submissions!
        // Test accounts have only a 500MB file quota.
        // NB: reorder tests by changing order of these enum values
        Initial, Complete, LateSubmission, TwoFiles, CorruptZip, ZipExtraFile, ZipMissingFile
        //TwoFiles, CorruptZip, ZipExtraFile, ZipMissingFile, Initial, Complete, LateSubmission
    }

    @RunWith(Parameterized.class)
    public static class ParamTests {

        static class Args {
            String labName;
            TestVariant variant;
            String completeFile;
            /**
             * points earned for submitting the initial version of the code
             */
            int pointsInitial;
            int pointsPossible;

            Args(String name, TestVariant tv, String complete, int initPts, int maxPts) {
                labName = name;
                variant = tv;
                completeFile = complete;
                pointsInitial = initPts;
                pointsPossible = maxPts;
            }

            @Override
            public String toString() {
                return labName + " " + variant;
            }
        }
        private final Args ARGS;

        @Parameterized.Parameters(name = "{0}")
        public static Collection ctorParams() {
            List<Object[]> p = new LinkedList<>();

            //for (TestVariant v : EnumSet.allOf(TestVariant.class)) {
            for (TestVariant v : EnumSet.of(LateSubmission)) {
//                p.add(new Object[]{new Args("lab1", v, "lab1_complete.zip", 28, 344)});
//                p.add(new Object[]{new Args("lab2div", v, "lc4_divider_complete.v", 0, 6000)});
//                p.add(new Object[]{new Args("lab2alu", v, "alu_complete.zip", 0, 24112)});
//                p.add(new Object[]{new Args("lab2gpn", v, "lc4_cla.v", 0, 2752512)});
                p.add(new Object[]{new Args("lab3alu", v, "single_complete.zip", 1964, 75810)});
                p.add(new Object[]{new Args("lab3full", v, "single_complete.zip", 88353, 1814570)});
                p.add(new Object[]{new Args("lab4alu", v, "pipeline_complete.zip", 0/*1964*/, 83405)});
                p.add(new Object[]{new Args("lab4full", v, "pipeline_complete.zip", 0/*88353*/, 2012063)});
                p.add(new Object[]{new Args("lab5alu", v, "superscalar_complete.zip", 0, 83962)});
//                p.add(new Object[]{new Args("lab5full", v, "superscalar_complete.zip", 0, 2163330)});

                // TODO: fix bug in Lab5full solution or ctrace
            }

            return p;
        }

        public ParamTests(Args a) {
            ARGS = a;
        }

        @Test
        public void submit() throws IOException, InterruptedException {

            assertTrue(asnOfName.containsKey(ARGS.labName));
            Assignment asn = asnOfName.get(ARGS.labName).assn;
            Lab lab = asnOfName.get(ARGS.labName).lab;
            boolean submitZip = lab.fileToSubmit.toString().endsWith(".zip");

            // set due date (always do this as previous test may have been LateSubmission)
            Common.TOKEN = Common.INSTRUCTOR_TOKEN;
            final ZonedDateTime due;
            if (LateSubmission == ARGS.variant) {
                // we just missed the grace period
                due = ZonedDateTime.now(ZoneId.of("UTC")).minus(GradeCoordinator.GRACE_PERIOD).minus(2, MINUTES);
            } else {
                due = ZonedDateTime.now(ZoneId.of("UTC")).plus(30, MINUTES);
            }
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id);
            AssignmentDueDate add = new AssignmentDueDate();
            add.due_at = DateTimeFormatter.ISO_INSTANT.format(due);
            Common.putJSON(url, add, "assignment");
            Thread.sleep(1_000); // wait a bit so due date change takes effect, sigh...

            // StudentA does the submission
            Common.TOKEN = STUDENTA_TOKEN;

            // if it's a group assignment, ensure that submitting student is part of the assignment group
            if (asn.isGroupAssignment()) {
                url = new GenericUrl(BASE_URL + "users/self/groups");
                List<Group> myGroups = Common.getAsList(url, Group[].class);
                assertEquals(1,myGroups.size());
                assertEquals("lab group 1", myGroups.get(0).name);
            }

            ProcessBuilder pb;

            // create zip file of initial state
            final Path repoLeafDir = GIT_WC_DIR.resolve(lab.repoLeafDir);
            final File initial = repoLeafDir.resolve(lab.fileToSubmit).toFile();
            if (submitZip) {
                if (initial.exists()) {
                    // recreate this file each time to keep tests idempotent
                    initial.delete();
                }
                String zipContents = String.join(" ", lab.archiveContents);
                // create fake .bit file if needed
                lab.archiveContents.stream().filter(f -> f.endsWith(".bit"))
                        .forEach(b -> {
                            try { FileUtils.copyFile(TINY_FILE, repoLeafDir.resolve(b).toFile());
                            } catch (IOException e) {
                                e.printStackTrace();
                                fail("error creating fake .bit file");
                            }
                        });
                // fill in files from previous labs
                lab.archiveContents.forEach(f -> {
                    if (repoLeafDir.resolve(f).toFile().canRead()) return; // don't need to pull this file in

                    Path src;
                    switch (f) {
                        case "lc4_divider.v":
                            src = GIT_WC_DIR.resolve("lab2-div").resolve(f);
                            break;
                        case "lc4_cla.v":
                        case "lc4_alu.v":
                            src = GIT_WC_DIR.resolve("lab2-alu").resolve(f);
                            break;
                        case "lc4_regfile.v":
                            src = GIT_WC_DIR.resolve("lab3-regfile").resolve(f);
                            break;
                        default:
                            return;
                    }
                    try {
                        FileUtils.copyFileToDirectory(src.toFile(), repoLeafDir.toFile());
                    } catch (IOException e) {
                        e.printStackTrace();
                        fail("error copying file from previous lab to create initial version");
                    }
                });
                pb = new ProcessBuilder("/bin/bash", "-c", "zip " + initial.toString() + " " + zipContents);
                pb.directory(repoLeafDir.toFile());
                check_call(pb);
            }

            // upload file
            final File fileToUpload;
            switch (ARGS.variant) {
                case Initial:
                case TwoFiles:
                    fileToUpload = initial;
                    break;
                case Complete: // these all use the complete version of the lab
                case LateSubmission:
                    fileToUpload = new File(RES_DIR, ARGS.completeFile);
                    break;
                case CorruptZip:
                    if (!submitZip) return;
                    fileToUpload = CORRUPT_ZIP;
                    break;
                case ZipExtraFile:
                    if (!submitZip) return;
                    pb = new ProcessBuilder("/bin/bash", "-c",
                            "zip " + initial.toString() + " " + TINY_FILE.toString());
                    pb.directory(repoLeafDir.toFile());
                    check_call(pb);
                    fileToUpload = initial;
                    break;
                case ZipMissingFile:
                    if (!submitZip) return;
                    Optional<String> fileToRemove = lab.archiveContents.stream().findFirst();
                    assertTrue(fileToRemove.isPresent());
                    pb = new ProcessBuilder("/bin/bash", "-c",
                            "zip -d " + initial.toString() + " " + fileToRemove.get());
                    pb.directory(repoLeafDir.toFile());
                    check_call(pb);
                    fileToUpload = initial;
                    break;
                default:
                    assert false : ARGS.variant;
                    return;
            }
            assertTrue(fileToUpload.toString(), fileToUpload.canRead());
            url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self/files");
            CanvasFile cfile = Common.uploadFile(url, fileToUpload);

            // perform submission
            GenericUrl post = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions");
            MakeSubmission ms = new MakeSubmission();
            ms.comment.text_comment = "upload from autograder";
            ms.submission.file_ids = new int[]{cfile.id};
            ms.submission.submission_type = "online_upload";
            if (TwoFiles == ARGS.variant) {
                url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self/files");
                if (Arrays.asList(asn.allowed_extensions).contains("v")) {
                    CanvasFile emptyCanFile = Common.uploadFile(url, TINY_FILE);
                    ms.submission.file_ids = new int[]{cfile.id, emptyCanFile.id};
                } else {
                    CanvasFile emptyCanFile = Common.uploadFile(url, CORRUPT_ZIP);
                    ms.submission.file_ids = new int[]{cfile.id, emptyCanFile.id};
                }
            }
            HttpResponse resp = Common.postJSON(post, ms, null);
            Submission submitted = resp.parseAs(Submission.class);
            submitted.parseTimes();
            String submittedTime = Common.canvasDateFormat(submitted.submitted_at);

            if (MEASURE_TIMING) {
                System.err.println(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            }
            // invoke the autograder
            toAG.write(Run.ch);
            int i = fromAG.read();
            assertTrue(i >= 0);
            //System.err.format("AG told us: %c %n", i);

            String[] tokens = new String[]{STUDENTA_TOKEN};
            if (asn.isGroupAssignment()) {
                // validate that other group member sees same results
                tokens = new String[]{STUDENTA_TOKEN, STUDENTB_TOKEN};
            }
            for (String token : tokens) {
                Common.TOKEN = token;

                // check for grade and comments
                url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self");
                url.put("include[]", "submission_comments");
                Submission mySub = Common.getAs(url, Submission.class);

                final double expectedFrac;
                switch (ARGS.variant) {
                    case Initial:
                        expectedFrac = ((double) ARGS.pointsInitial) / ARGS.pointsPossible;
                        break;
                    case Complete:
                        expectedFrac = 1.0;
                        break;
                    case TwoFiles:
                    case CorruptZip:
                    case ZipExtraFile:
                    case ZipMissingFile:
                        expectedFrac = 0.0;
                        break;
                    case LateSubmission:
                        expectedFrac = GradeCoordinator.LATE_PENALTY;
                        break;
                    default:
                        assert false;
                        return;
                }

                double canvasScore = Math.round(expectedFrac * asn.points_possible * 10.0) / 10.0;
                assertEquals(canvasScore, mySub.score, 0.0);

                { // invalid submission
                    SubmissionComment lastComment = mySub.submission_comments[mySub.submission_comments.length - 1];
                    assertTrue(lastComment.comment, lastComment.comment.contains(submittedTime));
                    switch (ARGS.variant) {
                        case TwoFiles:
                            assertTrue(lastComment.comment, lastComment.comment.contains("2 files were submitted, instead of just one"));
                            return;
                        case CorruptZip:
                            assertTrue(lastComment.comment, lastComment.comment.contains("appears corrupted, please submit again"));
                            return;
                        case ZipExtraFile:
                            assertTrue(lastComment.comment, lastComment.comment.contains("should not contain extraneous files"));
                            return;
                        case ZipMissingFile:
                            assertTrue(lastComment.comment, lastComment.comment.contains("is missing required file(s)"));
                            return;
                        default:
                            break;
                    }
                    // no further checking for invalid submissions
                }

                // check submission comments and attached files

                // each valid submission should have 2 comments: a pair of comments from AG
                // initial student comment doesn't show up for non-submitting group member
                assertTrue(mySub.submission_comments.length + " comments", mySub.submission_comments.length >= 2);

                SubmissionComment pendingComment = mySub.submission_comments[mySub.submission_comments.length - 2];
                SubmissionComment doneComment = mySub.submission_comments[mySub.submission_comments.length - 1];

                assertTrue(pendingComment.comment, pendingComment.comment.contains(submittedTime));
                assertTrue(pendingComment.comment, pendingComment.comment.contains("grading submission..."));

                assertTrue(doneComment.comment, doneComment.comment.contains(submittedTime));
                String points = String.format(" / %d tests passed = %.1f%% (%.1f points on Canvas) ",
                        ARGS.pointsPossible, expectedFrac * 100.0, canvasScore);
                assertTrue("expected '" + points + "' in " + doneComment.comment, doneComment.comment.contains(points));
                String lateStr = "points lost due to late submission";
                if (LateSubmission == ARGS.variant) {
                    assertTrue(doneComment.comment, doneComment.comment.contains(lateStr));
                } else {
                    assertFalse(doneComment.comment, doneComment.comment.contains(lateStr));
                }

                assertEquals(1, doneComment.attachments.length); // stderr is empty, therefore excluded
            } // end loop over group members



        } // end submit()

    } // end ParamTests

    public static class OneOffTests {

        /** A version of Lab 1 that generates a fake grade for itself */
        @Test
        public void fakeGrade() throws IOException, InterruptedException {
            Assignment asn = asnOfName.get("lab2div").assn;

            // set due date (always do this as previous test may have been LateSubmission)
            Common.TOKEN = Common.INSTRUCTOR_TOKEN;
            final ZonedDateTime due = ZonedDateTime.now(ZoneId.of("UTC")).plus(30, MINUTES);
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id);
            AssignmentDueDate add = new AssignmentDueDate();
            add.due_at = DateTimeFormatter.ISO_INSTANT.format(due);
            Common.putJSON(url, add, "assignment");

            // StudentA does the submission
            Common.TOKEN = STUDENTA_TOKEN;

            File fileToUpload = new File(RES_DIR, "lab2div-fakegrade.v");
            assertTrue(fileToUpload.toString(), fileToUpload.canRead());
            url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self/files");
            CanvasFile cfile = Common.uploadFile(url, fileToUpload);

            // perform submission
            GenericUrl post = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions");
            MakeSubmission ms = new MakeSubmission();
            ms.comment.text_comment = "upload from autograder";
            ms.submission.file_ids = new int[]{cfile.id};
            ms.submission.submission_type = "online_upload";

            HttpResponse resp = Common.postJSON(post, ms, null);
            Submission submitted = resp.parseAs(Submission.class);
            submitted.parseTimes();
            String submittedTime = Common.canvasDateFormat(submitted.submitted_at);

            // invoke the autograder
            toAG.write(Run.ch);
            int i = fromAG.read();
            assertTrue(i >= 0);

            // check for grade and comments
            url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self");
            url.put("include[]", "submission_comments");
            Submission mySub = Common.getAs(url, Submission.class);

            assertEquals(0, mySub.score, 0.0);
            SubmissionComment com = mySub.submission_comments[mySub.submission_comments.length-1];
            assertTrue(com.comment, com.comment.contains(submittedTime));
        }

        /** Have all four fake students submit Lab1 concurrently */
        @Test
        public void concurrentSubmissions() throws IOException {
            Assignment asn = asnOfName.get("lab1").assn;
            assertFalse(asn.isGroupAssignment());

            // set due date (always do this as previous test may have been LateSubmission)
            Common.TOKEN = Common.INSTRUCTOR_TOKEN;
            final ZonedDateTime due = ZonedDateTime.now(ZoneId.of("UTC")).plus(30, MINUTES);
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id);
            AssignmentDueDate add = new AssignmentDueDate();
            add.due_at = DateTimeFormatter.ISO_INSTANT.format(due);
            Common.putJSON(url, add, "assignment");

            File fileToUpload = new File(RES_DIR, "lab1_complete.zip");
            assertTrue(fileToUpload.toString(), fileToUpload.canRead());

            // each test student does a submission
            final List<String> TOKENS = Arrays.asList(STUDENTA_TOKEN, STUDENTB_TOKEN, STUDENTC_TOKEN, STUDENTD_TOKEN);
            TOKENS.forEach(token -> {
                Common.TOKEN = token;
                GenericUrl url2 = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self/files");
                try {
                    CanvasFile cfile = Common.uploadFile(url2, fileToUpload);
                    // perform submission
                    GenericUrl post = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions");
                    MakeSubmission ms = new MakeSubmission();
                    ms.comment.text_comment = "upload from autograder";
                    ms.submission.file_ids = new int[]{cfile.id};
                    ms.submission.submission_type = "online_upload";

                    HttpResponse resp = Common.postJSON(post, ms, null);
                    Submission sub = resp.parseAs(Submission.class);
                    assertNotNull(sub);
                } catch (IOException | InterruptedException e) {
                    fail(e.getLocalizedMessage());
                }
            });

            // put autograder into parallel mode
            toAG.write(ParallelGrading.ch);

            // invoke the autograder
            toAG.write(Run.ch);
            for (int i = 0; i < TOKENS.size(); i++) {
                int j = fromAG.read();
                assertTrue(j >= 0);
            }

            // restore serial grading for other tests
            toAG.write(SerialGrading.ch);

            for (String token : TOKENS) {
                TOKEN = token;

                url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id + "/submissions/self");
                url.put("include[]", "submission_comments");
                Submission mySub = Common.getAs(url, Submission.class);

                assertEquals(asn.points_possible, mySub.score);
                mySub.parseTimes();
                String submittedTime = Common.canvasDateFormat(mySub.submitted_at);
                SubmissionComment com = mySub.submission_comments[mySub.submission_comments.length-1];
                assertTrue(com.comment, com.comment.contains(submittedTime));
            }
        }

    }

}