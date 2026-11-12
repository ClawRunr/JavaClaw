package ai.javaclaw.approval;

/**
 * Why a tool call was not run. The same message reaches the model (as the tool's result) and the
 * owner (on the approval card or in the channel).
 */
public enum Denial {

    /** Denied, or nobody could be asked. */
    NOT_APPROVED("The user did not approve this action."),
    /** Nobody answered within {@code agent.approval.timeout}. */
    TIMED_OUT("No approval was given in time.");

    private final String message;

    Denial(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
