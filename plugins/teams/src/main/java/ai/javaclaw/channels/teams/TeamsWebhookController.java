package ai.javaclaw.channels.teams;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RestController
@ConditionalOnProperty(prefix = "agent.channels.teams", name = "enabled", havingValue = "true")
public class TeamsWebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeamsWebhookController.class);

    private final TeamsProperties properties;
    private final ChannelRegistry channelRegistry;
    private final Agent agent;
    private final TeamsChannel channel;
    private final Executor executor;

    public TeamsWebhookController(TeamsProperties properties, ChannelRegistry channelRegistry,
                                  Agent agent, TeamsChannel channel) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.agent = agent;
        this.channel = channel;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "teams-webhook-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PostMapping("/api/teams/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                        @RequestBody(required = false) TeamsActivityPayload activity) {
        if (!isRequestAuthorized(authHeader)) {
            return ResponseEntity.status(401).build();
        }
        if (activity == null || !"message".equals(activity.type())) {
            return ResponseEntity.ok().build();
        }
        if (!isAllowedUser(activity.from())) {
            LOGGER.warn("Ignoring Teams message from unauthorized user '{}'",
                    activity.from() == null ? null : activity.from().id());
            return ResponseEntity.ok().build();
        }
        String text = activity.text();
        if (text == null || text.isBlank()) {
            return ResponseEntity.ok().build();
        }

        String conversationId = activity.conversation() == null ? null : activity.conversation().id();
        channel.updateConversationReference(new TeamsConversationReference(activity.serviceUrl(), conversationId));

        channelRegistry.publishMessageReceivedEvent(
                new TeamsChannelMessageReceivedEvent(channel.getName(), text, conversationId));
        executor.execute(() -> handleMessage(conversationId, text));
        return ResponseEntity.ok().build();
    }

    private void handleMessage(String conversationId, String text) {
        try {
            String response = agent.respondTo(conversationId, text);
            channel.sendMessage(response);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle Teams message for conversation '{}'", conversationId, e);
        }
    }

    private boolean isAllowedUser(TeamsActivityPayload.From from) {
        String allowed = properties.getAllowedUserId();
        if (allowed == null || allowed.isBlank()) {
            return true; // not restricted — fine for local testing, not for production
        }
        return from != null && allowed.trim().equals(from.id());
    }

    /**
     * TODO (before exposing this endpoint publicly): validate the JWT in the Authorization
     * header against Bot Framework's JWKS (https://login.botframework.com/v1/.well-known/keys),
     * checking issuer, audience (= appId) and expiry. Currently this only checks a bearer
     * token is present, which is NOT sufficient authentication on its own.
     */
    private boolean isRequestAuthorized(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOGGER.warn("Rejected Teams webhook call with missing/invalid Authorization header");
            return false;
        }
        return true;
    }
}