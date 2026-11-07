package ai.javaclaw.approval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One tool call waiting for the owner. Everything a client needs to render the question is here,
 * and the decision it sends back must quote {@link #approvalId()} and {@link #argumentsHash()}.
 *
 * @param approvalId      single-use key; the tool call id when the model supplied a usable one
 * @param toolCallId      the model's id for the call (may be blank for some providers)
 * @param conversationId  the conversation whose turn is waiting
 * @param toolName        the tool the model wants to run
 * @param arguments       the exact JSON arguments the tool will receive
 * @param risk            the tool's risk, or {@code null} when nobody classified it
 * @param argumentsHash   SHA-256 of tool name plus canonical arguments ({@link ArgumentsHash})
 * @param decisions       the decisions a client may send back
 * @param details         extra context worth showing, e.g. {@code workingDirectory}, {@code subagentType}
 * @param protectedReason why this call always asks regardless of rules, or {@code null}
 * @param expiresAt       when the request times out and counts as denied
 */
public record ApprovalRequest(
        String approvalId,
        String toolCallId,
        String conversationId,
        String toolName,
        String arguments,
        ToolRisk risk,
        String argumentsHash,
        List<ApprovalDecision> decisions,
        Map<String, String> details,
        String protectedReason,
        Instant expiresAt) {

    public ApprovalRequest {
        decisions = List.copyOf(decisions);
        details = Map.copyOf(details);
    }
}
