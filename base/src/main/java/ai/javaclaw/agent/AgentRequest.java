package ai.javaclaw.agent;

/**
 * One request to the {@link Agent}.
 *
 * @param conversation the conversation the request belongs to
 * @param sessionId    the session of that conversation the request goes to; null for the
 *                     conversation's active session (see {@link ChatSessions})
 * @param kind         what a session this starts is for; an existing session keeps its kind
 * @param channel      where a session this starts came from; an existing session keeps its channel
 * @param input        the message or prompt
 */
public record AgentRequest(ConversationKey conversation, String sessionId, SessionKind kind, SessionChannel channel, String input) {

    /** A request to the conversation's active session. */
    public AgentRequest(ConversationKey conversation, SessionKind kind, SessionChannel channel, String input) {
        this(conversation, null, kind, channel, input);
    }

    /** A message the owner sent in a chat on {@code channel}, to the conversation's active session. */
    public static AgentRequest chat(ConversationKey conversation, SessionChannel channel, String message) {
        return new AgentRequest(conversation, null, SessionKind.CHAT, channel, message);
    }

    /** A message the owner sent in one particular session of a chat on {@code channel}. */
    public static AgentRequest chat(ConversationKey conversation, String sessionId, SessionChannel channel, String message) {
        return new AgentRequest(conversation, sessionId, SessionKind.CHAT, channel, message);
    }
}
