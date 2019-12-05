package canvas;

public class ProcessOutput {
    public final String stdout;
    public final String stderr;

    public ProcessOutput(String stdout, String stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }
}
