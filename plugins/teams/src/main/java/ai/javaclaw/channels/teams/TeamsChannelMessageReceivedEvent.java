package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ChannelMessageReceivedEvent;

public class TeamsChannelMessageReceivedEvent extends ChannelMessageReceivedEvent {

    private final String conversationId;

    public TeamsChannelMessageReceivedEvent(String channel, String message, String conversationId) {
        super(channel, message);
        this.conversationId = conversationId;
    }

    public String getConversationId() {
        return conversationId;
    }
}