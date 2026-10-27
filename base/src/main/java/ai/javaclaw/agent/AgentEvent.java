package ai.javaclaw.agent;

/**
 * Something the agent produced while answering. Callers that render progress consume these as a
 * single stream; the sequence is zero or more {@link Token}/{@link ToolCall}/{@link ToolResult}
 * events, terminated by exactly one {@link Done} or {@link Failed}.
 */
public sealed interface AgentEvent {

    /** A piece of the answer, as it arrives from the model. */
    record Token(String text) implements AgentEvent {}

    /** The model asked for a tool; {@code input} is the raw JSON arguments. */
    record ToolCall(String id, String name, String input) implements AgentEvent {}

    /** A tool returned. {@code id} matches the {@link ToolCall} that started it. */
    record ToolResult(String id, String name, String output) implements AgentEvent {}

    /** The answer is complete. Always the last event. */
    record Done() implements AgentEvent {}

    /** The answer failed. Always the last event; tokens already emitted still stand. */
    record Failed(String message) implements AgentEvent {}
}
