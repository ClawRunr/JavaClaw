package ai.javaclaw.approval;

/**
 * Hears what the gate does during one turn: an approval it is waiting for, and the outcome of
 * every call it did not simply run. The agent turns these into {@code AgentEvent}s.
 */
public interface ApprovalListener {

    ApprovalListener NONE = new ApprovalListener() {
        @Override
        public void requested(ApprovalRequest request) {
        }

        @Override
        public void resolved(ApprovalOutcome outcome) {
        }
    };

    /** The gate is now waiting for the owner to answer this request. */
    void requested(ApprovalRequest request);

    /** A call was approved after asking, or was not run (with the reason). */
    void resolved(ApprovalOutcome outcome);

    /**
     * @param approvalId the request it answers, or {@code null} when the call was refused without asking
     * @param toolCallId the model's id for the call
     * @param toolName   the tool
     * @param denial     why it was not run, or {@code null} when it was approved
     */
    record ApprovalOutcome(String approvalId, String toolCallId, String toolName, Denial denial) {

        public boolean approved() {
            return denial == null;
        }
    }
}
