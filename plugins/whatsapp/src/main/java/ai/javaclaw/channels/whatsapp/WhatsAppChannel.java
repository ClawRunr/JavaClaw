package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelApprovals;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Puts the agent behind {@link WhatsApp}: it answers what arrives there, and it is where replies
 * to background tasks are delivered.
 */
public class WhatsAppChannel implements Channel {

    static final String CHANNEL_ID = "whatsapp";

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppChannel.class);

    private final WhatsApp whatsApp;
    private final ChannelRegistry channelRegistry;
    private final ChannelApprovals approvals;

    public WhatsAppChannel(WhatsApp whatsApp, ChannelRegistry channelRegistry, ChannelApprovals approvals) {
        this.whatsApp = whatsApp;
        this.channelRegistry = channelRegistry;
        this.approvals = approvals;
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

    /** Only messages from the configured chat get here; {@link WhatsApp} drops the rest. */
    void onMessage(String fromJidId, String message) {
        Consumer<String> reply = whatsApp::sendMessage;
        // /approve and /deny are answered here, on the webhook worker, and never reach the model
        if (approvals.handleCommand(fromJidId, message, reply)) {
            return;
        }
        channelRegistry.publishMessageReceivedEvent(new WhatsAppChannelMessageReceivedEvent(getName(), message, fromJidId));
        // The turn runs in the background, so the single webhook worker can deliver an /approve it waits for
        approvals.respond(fromJidId, message, new WhatsAppChat(),
                e -> LOGGER.error("Failed to handle WhatsApp message for chat '{}'", fromJidId, e));
    }

    /**
     * The configured chat. Approval requests use WhatsApp's own markup; wacli cannot edit a sent
     * message, so the outcome of a request arrives as a short reply.
     */
    private final class WhatsAppChat implements ApprovalChat {

        @Override
        public ApprovalFormat format() {
            return ApprovalFormat.WHATSAPP;
        }

        @Override
        public void send(String text) {
            whatsApp.sendMessage(text);
        }

        @Override
        public void sendFormatted(String formatted) {
            whatsApp.sendMessage(formatted);
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
