package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.ChannelMessageReceivedEvent;

public class WhatsAppChannelMessageReceivedEvent extends ChannelMessageReceivedEvent {

    private final String conversationId;

    public WhatsAppChannelMessageReceivedEvent(String channel, String message, String conversationId) {
        super(channel, message);
        this.conversationId = conversationId;
    }

    public String getConversationId() {
        return conversationId;
    }
}
