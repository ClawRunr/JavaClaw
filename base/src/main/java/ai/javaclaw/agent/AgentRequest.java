package ai.javaclaw.agent;

/**
 * One request to the {@link Agent}.
 *
 * @param conversation the conversation the request belongs to; it goes to that conversation's
 *                     active session (see {@link ChatSessions})
 * @param kind         what a session this starts is for; an existing session keeps its kind
 * @param channel      where a session this starts came from; an existing session keeps its channel
 * @param input        the message or prompt
 */
public record AgentRequest(ConversationKey conversation, SessionKind kind, SessionChannel channel, String input) {

    /** A message the owner sent in a chat on {@code channel}. */
    public static AgentRequest chat(ConversationKey conversation, SessionChannel channel, String message) {
        return new AgentRequest(conversation, SessionKind.CHAT, channel, message);
    }
}
