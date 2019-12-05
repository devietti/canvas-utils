package canvas;

import canvas.apiobjects.*;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.format.DateTimeParseException;
import java.util.*;

public class DownloadQuizResponses {

    public static void main(String[] args) throws IOException {

        int critpathDelayId = -1, critpathLogicLevelsId = -1;
        List<Double> criticalPathDelays = new LinkedList<>();
        List<Double> criticalPathLogicLevels = new LinkedList<>();

        // read command-line args
        if (args.length != 1) {
            System.out.println("Usage: " + DownloadQuizResponses.class.getName() + " output.csv");
            return;
        }
        final String CSV_FILE = args[0];

        Common.setup();
        final String quizId = Common.pickQuiz("timing results");

        // figure out which QuizQuestion(s) we want the answers for
        List<QuizQuestion> questions = Common.getAsList("quizzes/" + quizId + "/questions", QuizQuestion[].class);

        for (QuizQuestion qq : questions) {
            switch (qq.question_name) {
                case "critical path delay":
                    critpathDelayId = qq.id;
                    break;
                case "critical path length":
                    critpathLogicLevelsId = qq.id;
                    break;
            }
        }
        assert (critpathDelayId != -1 && critpathLogicLevelsId != -1);

        QuizSubmissionsWrapper quizSubsWrapper = Common.getAs("quizzes/" + quizId + "/submissions",
                QuizSubmissionsWrapper.class);

        Map<Integer,QuizSubmission> mostRecentSubmissions = new HashMap<>();
//        for (QuizSubmissionsWrapper qsw : quizSubsWrappers) {
            for (QuizSubmission qsub : quizSubsWrapper.quiz_submissions) {
                try {
                    qsub.finished_at = Common.parseCanvasDate(qsub.finished_at_string);
                } catch (DateTimeParseException e) {
                    System.err.format("*** Invalid finished_at (%s) for user %d. Skipping...%n",
                            qsub.finished_at_string, qsub.user_id);
                    continue;
                }

                if (!mostRecentSubmissions.containsKey(qsub)) {
                    mostRecentSubmissions.put(qsub.user_id, qsub);
                } else { // check if qsub is newer than entry in mostRecentSubmissions
                    QuizSubmission existing = mostRecentSubmissions.get(qsub.user_id);
                    if (qsub.finished_at.isAfter(existing.finished_at)) {
                        mostRecentSubmissions.put(qsub.user_id, qsub);
                    }
                }
            }
        //}

        for (QuizSubmission qsub : mostRecentSubmissions.values()) {
            QuizSubmissionEventsWrapper qsew = Common.getAs("quizzes/" + quizId + "/submissions/" + qsub.id + "/events",
                    QuizSubmissionEventsWrapper.class);

            for (QuizSubmissionEvent qse : qsew.quiz_submission_events) {
                if (qse.event_type.equals("question_answered")) {
                    assert qse.containsKey("event_data");
                    GsonFactory gsf = GsonFactory.getDefaultInstance();
                    QuizAnswer[] answers = gsf.fromString(qse.get("event_data").toString(), QuizAnswer[].class);
                    for (QuizAnswer qa : answers) {
                        Object answer = qa.get("answer");

                        // hack: Somehow, GsonFactory is putting in the name of a Java object as a string...
                        if (answer.toString().contains("java.lang.Object@")) continue;

                        if (qa.quiz_question_id == critpathDelayId) {
                            criticalPathDelays.add(Double.parseDouble(answer.toString()));
                        } else if (qa.quiz_question_id == critpathLogicLevelsId) {
                            criticalPathLogicLevels.add(Double.parseDouble(answer.toString()));
                        }

                    }
                }
            }
        }

        // generate CSV file of the answers
        Writer w = new FileWriter(CSV_FILE);
        CSVPrinter csvPrinter = new CSVPrinter(w, CSVFormat.DEFAULT
                .withHeader("CP Delay (ns)", "CP Logic Levels"));

        for (int i = 0; i < Math.max(criticalPathDelays.size(), criticalPathLogicLevels.size()); i++) {
            double delay = i < criticalPathDelays.size() ? criticalPathDelays.get(i) : -1 ;
            double logiclevels = i < criticalPathLogicLevels.size() ? criticalPathLogicLevels.get(i) : -1;

            csvPrinter.printRecord(delay, logiclevels);
            csvPrinter.flush();
        }
        csvPrinter.close();
    }
}
