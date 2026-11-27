package ai.javaclaw.agent;

/**
 * What a session is for, stored in its metadata under {@link #ATTRIBUTE}: JavaClaw's addition to
 * Spring AI's {@code SessionAttributes}.
 */
public enum SessionKind {

    /** A conversation with the owner, in any channel. */
    CHAT("chat"),

    /** The conversation a scheduled task runs in. */
    TASK("task");

    /** The session metadata attribute that holds the kind. */
    public static final String ATTRIBUTE = "kind";

    /** The prefix of every task conversation's id. */
    public static final String TASK_ID_PREFIX = "task-";

    private final String value;

    SessionKind(String value) {
        this.value = value;
    }

    /** The value stored in the session's metadata. */
    public String value() {
        return value;
    }
}
