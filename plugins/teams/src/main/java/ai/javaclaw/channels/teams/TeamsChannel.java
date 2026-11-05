package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class TeamsChannel implements Channel {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeamsChannel.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String CHANNEL_ID = "teams";

    private final BotFrameworkTokenProvider tokenProvider;
    private final HttpClient httpClient;
    private final AtomicReference<TeamsConversationReference> lastConversationReference =
            new AtomicReference<>();

    public TeamsChannel(BotFrameworkTokenProvider tokenProvider, ChannelRegistry channelRegistry) {
        this(tokenProvider, HttpClient.newHttpClient(), channelRegistry);
    }

    TeamsChannel(BotFrameworkTokenProvider tokenProvider, HttpClient httpClient, ChannelRegistry channelRegistry) {
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        channelRegistry.registerChannel(this);
        LOGGER.info("Started Microsoft Teams channel");
    }

    @Override
    public String getName() {
        return CHANNEL_ID;
    }

    /**
     * Called by the webhook controller whenever a message arrives from the allowed user, so that
     * {@link #sendMessage(String)} (used for messages the agent sends on its own, e.g. from tasks)
     * knows where to go.
     */
    void updateConversationReference(TeamsConversationReference reference) {
        lastConversationReference.set(reference);
    }

    /** Sends to the last conversation the allowed user wrote in. */
    @Override
    public void sendMessage(String message) {
        TeamsConversationReference reference = lastConversationReference.get();
        if (reference == null) {
            LOGGER.warn("No known Teams conversation yet, cannot send a message of {} characters",
                    message == null ? 0 : message.length());
            return;
        }
        sendMessage(reference, message);
    }

    /** Sends to the given conversation, e.g. as the reply to the message that came from it. */
    public void sendMessage(TeamsConversationReference reference, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String url = reference.serviceUrl()
                + (reference.serviceUrl().endsWith("/") ? "" : "/")
                + "v3/conversations/" + reference.conversationId() + "/activities";

        try {
            String body = JSON.writeValueAsString(Map.of("type", "message", "text", message));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + tokenProvider.getToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOGGER.error("Teams send to conversation '{}' failed with status {}: {}",
                        reference.conversationId(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send Teams message to conversation '{}'", reference.conversationId(), e);
        }
    }
}
