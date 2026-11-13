package ai.javaclaw.agent;

import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;

/**
 * Something the agent produced while answering. Callers that render progress consume these as a
 * single stream; the sequence is zero or more {@link Token}/{@link ToolCall}/{@link ToolResult}/
 * {@link ApprovalRequested}/{@link ApprovalResolved} events, terminated by exactly one
 * {@link Done} or {@link Failed}.
 */
public sealed interface AgentEvent {

    /** A piece of the answer, as it arrives from the model. */
    record Token(String text) implements AgentEvent {}

    /** The model asked for a tool; {@code input} is the raw JSON arguments. */
    record ToolCall(String id, String name, String input) implements AgentEvent {}

    /** A tool returned, or was not run. {@code id} matches the {@link ToolCall} that started it. */
    record ToolResult(String id, String name, String output) implements AgentEvent {}

    /**
     * The turn is paused until the owner allows or denies this call. A client shows the request and
     * answers it with one of {@link ApprovalRequest#decisions()}; the turn itself ends with
     * {@link Done}.
     */
    record ApprovalRequested(ApprovalRequest request) implements AgentEvent {}

    /**
     * A call that needed a decision has one: approved ({@code denial == null}), or not run, with the
     * reason. Calls refused without asking (nobody to ask) have no {@code approvalId}.
     */
    record ApprovalResolved(String approvalId, String toolCallId, String toolName, Denial denial) implements AgentEvent {

        public boolean approved() {
            return denial == null;
        }
    }

    /** The answer is complete, or paused for approval. Always the last event. */
    record Done() implements AgentEvent {}

    /** The answer failed. Always the last event; tokens already emitted still stand. */
    record Failed(String message) implements AgentEvent {}
}
