package canvas.autograder;

/** Each SocketMessage has a char value so we can easily send these via telnet */
public enum SocketMessage {
    Nop(' '),
    Run('r'),
    Quit('q'),
    ParallelGrading('p'),
    SerialGrading('s'),
    GradedSuccessfully('t'),
    ProblemGrading('f');

    public final char ch;

    SocketMessage(char c) {
        this.ch = c;
    }
}

