package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the agent behind {@link WhatsApp}: it answers what arrives there, and it is where replies
 * to background tasks are delivered.
 */
public class WhatsAppChannel implements Channel {

    static final String CHANNEL_ID = "whatsapp";

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppChannel.class);

    private final WhatsApp whatsApp;
    private final ChannelRegistry channelRegistry;
    private final Agent agent;

    public WhatsAppChannel(WhatsApp whatsApp, ChannelRegistry channelRegistry, Agent agent) {
        this.whatsApp = whatsApp;
        this.channelRegistry = channelRegistry;
        this.agent = agent;
    }

    @Override
    public String getName() {
        return CHANNEL_ID;
    }

    @PostConstruct
    public void start() {
        if (!whatsApp.start()) {
            return;
        }
        whatsApp.registerMessageReceiver(this::onMessage);
        channelRegistry.registerChannel(this);
        LOGGER.info("Started WhatsApp integration via wacli");
    }

    @PreDestroy
    public void stop() {
        whatsApp.stop();
        channelRegistry.unregisterChannel(this);
    }

    @Override
    public void sendMessage(String message) {
        whatsApp.sendMessage(message);
    }

    void onMessage(String fromJidId, String message) {
        channelRegistry.publishMessageReceivedEvent(new WhatsAppChannelMessageReceivedEvent(getName(), message, fromJidId));
        try {
            whatsApp.sendMessage(agent.respondTo(AgentRequest.chat(fromJidId, SessionChannel.WHATSAPP, message)));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle WhatsApp message for chat '{}'", fromJidId, e);
        }
    }

    static class WhatsAppChannelMessageReceivedEvent extends ChannelMessageReceivedEvent {

        private final String conversationId;

        public WhatsAppChannelMessageReceivedEvent(String channel, String message, String conversationId) {
            super(channel, message);
            this.conversationId = conversationId;
        }

        public String getConversationId() {
            return conversationId;
        }
    }
}
