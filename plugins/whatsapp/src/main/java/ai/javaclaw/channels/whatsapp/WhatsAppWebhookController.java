package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@ConditionalOnProperty(prefix = "agent.channels.whatsapp", name = "enabled", havingValue = "true")
public class WhatsAppWebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WacliProperties properties;
    private final ChannelRegistry channelRegistry;
    private final Agent agent;
    private final WacliWhatsAppChannel channel;
    private final Executor executor;

    @Autowired
    public WhatsAppWebhookController(WacliProperties properties, ChannelRegistry channelRegistry,
                                     Agent agent, WacliWhatsAppChannel channel) {
        this(properties, channelRegistry, agent, channel, defaultExecutor());
    }

    WhatsAppWebhookController(WacliProperties properties, ChannelRegistry channelRegistry,
                             Agent agent, WacliWhatsAppChannel channel, Executor executor) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.agent = agent;
        this.channel = channel;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "whatsapp-webhook-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    @PostMapping("/api/whatsapp/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) WacliWebhookPayload payload) {
        if (payload == null) {
            return ResponseEntity.ok().build();
        }

        if (channel.wasSentByAgent(payload.id())) {
            // Our own outbound replies are echoed back by 'wacli sync'. Drop them by message ID
            // (not FromMe) so the agent does not answer itself in a loop -- keying on the ID is what
            // lets you drive the assistant from your own "Message Yourself" chat on a single number,
            // where the messages you type also carry FromMe=true.
            return ResponseEntity.ok().build();
        }

        if (!isAllowedChat(payload.chat())) {
            LOGGER.warn("Ignoring WhatsApp message from unauthorized chat '{}'", payload.chat());
            return ResponseEntity.ok().build();
        }
        String text = payload.text();
        if (text == null || text.isBlank()) {
            return ResponseEntity.ok().build();
        }

        String conversationId = payload.chat();
        channelRegistry.publishMessageReceivedEvent(
                new WhatsAppChannelMessageReceivedEvent(channel.getName(), text, conversationId));
        executor.execute(() -> handleMessage(conversationId, text));
        return ResponseEntity.ok().build();
    }

    private void handleMessage(String conversationId, String text) {
        try {
            String response = agent.respondTo(conversationId, text);
            channel.sendMessage(response);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle WhatsApp message for chat '{}'", conversationId, e);
        }
    }

    private boolean isAllowedChat(String chatJid) {
        String allowed = properties.getAllowedChatJid();
        if (allowed == null || allowed.isBlank() || chatJid == null) {
            return false;
        }
        return allowed.trim().equalsIgnoreCase(chatJid.trim());
    }
}
