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
    private final BotFrameworkJwtValidator jwtValidator;
    private final Executor executor;

    public TeamsWebhookController(TeamsProperties properties, ChannelRegistry channelRegistry,
                                  Agent agent, TeamsChannel channel, BotFrameworkJwtValidator jwtValidator) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.agent = agent;
        this.channel = channel;
        this.jwtValidator = jwtValidator;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "teams-webhook-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PostMapping("/api/teams/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                        @RequestBody(required = false) TeamsActivityPayload activity) {
        // The token is bound to the activity's serviceUrl, so a request without a body cannot be authorized
        if (!isRequestAuthorized(authHeader, activity == null ? null : activity.serviceUrl())) {
            return ResponseEntity.status(401).build();
        }
        if (!"message".equals(activity.type())) {
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
        String teamsConversationId = activity.conversation() == null ? null : activity.conversation().id();
        if (teamsConversationId == null || teamsConversationId.isBlank()) {
            LOGGER.warn("Ignoring Teams message without a conversation id");
            return ResponseEntity.ok().build();
        }

        // Captured per message, so the reply goes back to this conversation even if another
        // message (from another conversation) arrives while the agent is still working on this one
        TeamsConversationReference reference =
                new TeamsConversationReference(activity.serviceUrl(), teamsConversationId);
        channel.updateConversationReference(reference);

        channelRegistry.publishMessageReceivedEvent(
                new TeamsChannelMessageReceivedEvent(channel.getName(), text, teamsConversationId));
        executor.execute(() -> handleMessage(reference, text));
        return ResponseEntity.ok().build();
    }

    private void handleMessage(TeamsConversationReference reference, String text) {
        String conversationId = getConversationId(reference.conversationId());
        try {
            String response = agent.respondTo(conversationId, text);
            channel.sendMessage(reference, response);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle Teams message for conversation '{}'", conversationId, e);
        }
    }

    /**
     * Only the configured user may talk to the agent. Teams sends both the Teams user ID
     * ({@code from.id}, {@code 29:...}) and the Entra object ID ({@code from.aadObjectId}),
     * and either may be configured. Without a configured user, every message is rejected.
     */
    private boolean isAllowedUser(TeamsActivityPayload.From from) {
        String allowed = properties.getAllowedUserId();
        if (allowed == null || allowed.isBlank()) {
            LOGGER.warn("No agent.channels.teams.allowed-user-id configured, rejecting all Teams messages");
            return false;
        }
        String allowedUserId = allowed.trim();
        return from != null && (allowedUserId.equals(from.id()) || allowedUserId.equalsIgnoreCase(from.aadObjectId()));
    }

    private boolean isRequestAuthorized(String authHeader, String serviceUrl) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOGGER.warn("Rejected Teams webhook call with missing/invalid Authorization header");
            return false;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return jwtValidator.isValid(token, serviceUrl);
    }

    private static String getConversationId(String teamsConversationId) {
        return "teams-" + teamsConversationId;
    }
}
