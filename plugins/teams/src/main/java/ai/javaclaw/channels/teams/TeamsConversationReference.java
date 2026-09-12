package ai.javaclaw.channels.teams;

/**
 * The minimum needed to POST a proactive reply back through the Bot Framework
 * Connector API: where to send it (serviceUrl) and which conversation it belongs to.
 */
public record TeamsConversationReference(String serviceUrl, String conversationId) {
}