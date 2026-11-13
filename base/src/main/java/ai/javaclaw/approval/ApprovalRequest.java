package ai.javaclaw.approval;

import java.util.List;

/**
 * One tool call of a paused reply that waits for the owner. Everything a client needs to render
 * the question is here, and the decision it sends back must quote {@link #approvalId()} and
 * {@link #argumentsHash()}.
 *
 * @param approvalId     single-use key, random
 * @param toolCallId     the model's id for the call (may be blank for some providers)
 * @param conversationId the conversation that is paused
 * @param toolName       the tool the model wants to run
 * @param arguments      the exact JSON arguments the tool will receive
 * @param argumentsHash  SHA-256 of tool name plus canonical arguments ({@link ArgumentsHash})
 * @param decisions      the decisions a client may send back
 */
public record ApprovalRequest(
        String approvalId,
        String toolCallId,
        String conversationId,
        String toolName,
        String arguments,
        String argumentsHash,
        List<ApprovalDecision> decisions) {

    public ApprovalRequest {
        decisions = List.copyOf(decisions);
    }
}
