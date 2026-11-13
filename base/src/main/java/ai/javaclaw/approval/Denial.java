package ai.javaclaw.approval;

/**
 * Why a tool call was not run. The same message reaches the model (as the tool's result) and the
 * owner (on the approval card or in the channel).
 */
public enum Denial {

    /** The owner denied it. */
    NOT_APPROVED("The user did not approve this action."),
    /** A new message arrived while the call was still waiting. */
    MOVED_ON("The user moved on without approving."),
    /** Nobody can answer here: a scheduled task, a group chat, or a client that cannot ask. */
    NOBODY_TO_ASK("No one could be asked to approve this action here, so it was not run.");

    private final String message;

    Denial(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
