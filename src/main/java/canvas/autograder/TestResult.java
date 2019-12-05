package canvas.autograder;

import com.google.api.client.util.Key;

public class TestResult {
    @Key
    public Double pointsEarned;
    @Key
    public Double pointsPossible;
    @Key
    public String stdoutFile;
    @Key
    public String stderrFile;
    /** This submission is flagged for review, email instructor about it. */
    @Key
    public boolean flagged = false;

    /** empty ctor needed for GSON framework */
    public TestResult() {}

    /**
     * All fields may be null. If the test runs successfully, all fields will be non-null. Additionally, pointsEarned
     * and pointsPossible should both be null or both non-null.
     */
    TestResult(Double earned, Double possible, String outF, String errF) {
        pointsEarned = earned;
        pointsPossible = possible;
        stdoutFile = outF;
        stderrFile = errF;
    }
}
