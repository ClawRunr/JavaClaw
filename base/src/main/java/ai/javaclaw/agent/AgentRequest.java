package ai.javaclaw.agent;

/**
 * One request to the {@link Agent}.
 *
 * @param conversationId the conversation, and so the session, the request belongs to
 * @param kind           what a session this starts is for; an existing session keeps its kind
 * @param channel        where a session this starts came from; an existing session keeps its channel
 * @param input          the message or prompt
 */
public record AgentRequest(String conversationId, SessionKind kind, SessionChannel channel, String input) {

    /** A message the owner sent in a chat on {@code channel}. */
    public static AgentRequest chat(String conversationId, SessionChannel channel, String message) {
        return new AgentRequest(conversationId, SessionKind.CHAT, channel, message);
    }
}
