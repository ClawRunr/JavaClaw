package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;
import ai.javaclaw.channels.ChannelApprovals;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Consumer;

@RestController
@ConditionalOnProperty(prefix = "agent.channels.teams", name = "enabled", havingValue = "true")
public class TeamsWebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeamsWebhookController.class);
    private static final String PERSONAL_CONVERSATION = "personal";
    static final String ERROR_MESSAGE = "Sorry, something went wrong while handling your message.";

    private final TeamsProperties properties;
    private final ChannelRegistry channelRegistry;
    private final ChannelApprovals approvals;
    private final TeamsChannel channel;
    private final BotFrameworkJwtValidator jwtValidator;

    public TeamsWebhookController(TeamsProperties properties, ChannelRegistry channelRegistry,
                                  ChannelApprovals approvals, TeamsChannel channel, BotFrameworkJwtValidator jwtValidator) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.approvals = approvals;
        this.channel = channel;
        this.jwtValidator = jwtValidator;
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
        // Replies can contain shell output or file contents, so they must never land in a channel or group chat
        String conversationType = activity.conversation() == null ? null : activity.conversation().conversationType();
        if (!PERSONAL_CONVERSATION.equals(conversationType)) {
            LOGGER.warn("Ignoring Teams message in a '{}' conversation, only personal chats are supported",
                    conversationType);
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
        String conversationId = getConversationId(teamsConversationId);
        Consumer<String> reply = message -> channel.sendMessage(reference, message);

        // /approve and /deny from the allowed user in a personal chat are answered here and never reach the model
        if (approvals.handleCommand(conversationId, text, reply)) {
            return ResponseEntity.ok().build();
        }
        channel.updateConversationReference(reference);
        channelRegistry.publishMessageReceivedEvent(
                new TeamsChannelMessageReceivedEvent(channel.getName(), text, teamsConversationId));
        // Answered in the background: Teams expects the webhook to return quickly, and the turn may wait for an /approve
        approvals.respond(conversationId, text, new TeamsChat(channel, reference), e -> {
            LOGGER.error("Failed to handle Teams message for conversation '{}'", conversationId, e);
            sendErrorMessage(reference);
        });
        return ResponseEntity.ok().build();
    }

    /**
     * The personal chat a turn came from. Approval requests are sent in Teams markdown.
     */
    private record TeamsChat(TeamsChannel channel, TeamsConversationReference reference) implements ApprovalChat {

        @Override
        public ApprovalFormat format() {
            return ApprovalFormat.TEAMS_MARKDOWN;
        }

        @Override
        public void send(String text) {
            channel.sendMessage(reference, text);
        }

        @Override
        public void sendFormatted(String markdown) {
            channel.sendMarkdown(reference, markdown);
        }
    }

    /** Lets the user know the message was not handled, without exposing any error details. */
    private void sendErrorMessage(TeamsConversationReference reference) {
        try {
            channel.sendMessage(reference, ERROR_MESSAGE);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to send error message to Teams conversation '{}'", reference.conversationId(), e);
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
