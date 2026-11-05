package ai.javaclaw.channels.teams;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamsWebhookControllerTest {

    private static final long WAIT_FOR_BACKGROUND_WORK_MILLIS = 1_000;
    private static final String SERVICE_URL = "https://smba.example/";
    private static final String ALLOWED_USER = "29:allowed-user";

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private Agent agent;

    @Mock
    private TeamsChannel channel;

    @Mock
    private BotFrameworkJwtValidator jwtValidator;

    private TeamsWebhookController controller(String allowedUserId) {
        TeamsProperties properties = new TeamsProperties();
        properties.setAllowedUserId(allowedUserId);
        // Most tests send "Bearer t"; lenient, because tests without a Bearer header never reach the validator
        lenient().when(jwtValidator.isValid("t", SERVICE_URL)).thenReturn(true);
        return new TeamsWebhookController(properties, channelRegistry, agent, channel, jwtValidator);
    }

    // -----------------------------------------------------------------------
    // Authorization
    // -----------------------------------------------------------------------

    @Test
    void rejectsRequestsWithoutBearerToken() {
        ResponseEntity<Void> response =
                controller(ALLOWED_USER).webhook(null, activity("message", ALLOWED_USER, "hello", "conv-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(agent, channel, channelRegistry, jwtValidator);
    }

    @Test
    void rejectsRequestWithInvalidJwt() {
        when(jwtValidator.isValid("bad-token", SERVICE_URL)).thenReturn(false);

        ResponseEntity<Void> response =
                controller(ALLOWED_USER).webhook("Bearer bad-token", activity("message", ALLOWED_USER, "hello", "conv-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void passesTheActivityServiceUrlToTheValidator() {
        controller(ALLOWED_USER).webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1"));

        verify(jwtValidator).isValid("t", SERVICE_URL);
    }

    @Test
    void rejectsAnEmptyBodyBecauseThereIsNoServiceUrlToCheckTheTokenAgainst() {
        ResponseEntity<Void> response = controller(ALLOWED_USER).webhook("Bearer t", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    // -----------------------------------------------------------------------
    // Ignored activities
    // -----------------------------------------------------------------------

    @Test
    void ignoresNonMessageActivities() {
        controller(ALLOWED_USER).webhook("Bearer t", activity("conversationUpdate", ALLOWED_USER, null, "conv-1"));

        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresActivitiesWithoutText() {
        controller(ALLOWED_USER).webhook("Bearer t", activity("message", ALLOWED_USER, null, "conv-1"));

        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresActivitiesWithoutConversationId() {
        ResponseEntity<Void> response =
                controller(ALLOWED_USER).webhook("Bearer t", activity("message", ALLOWED_USER, "hello", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    // -----------------------------------------------------------------------
    // User matching
    // -----------------------------------------------------------------------

    @Test
    void ignoresMessagesFromUnauthorizedUser() {
        controller(ALLOWED_USER).webhook("Bearer t", activity("message", "29:other-user", "hello", "conv-1"));

        verify(agent, never()).respondTo(anyString(), anyString());
        verifyNoInteractions(channel);
    }

    @Test
    void rejectsEveryoneWhenNoAllowedUserIsConfigured() {
        controller(null).webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1"));
        controller("  ").webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1"));

        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void acceptsMessagesFromTheConfiguredTeamsUserId() {
        when(agent.respondTo("teams-conv-1", "hello")).thenReturn("hi!");

        controller(ALLOWED_USER).webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1"));

        verify(agent, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).respondTo("teams-conv-1", "hello");
    }

    @Test
    void acceptsMessagesFromTheConfiguredEntraObjectId() {
        when(agent.respondTo("teams-conv-1", "hello")).thenReturn("hi!");
        TeamsActivityPayload activity = new TeamsActivityPayload("message", "activity-1", SERVICE_URL, "msteams", "hello",
                new TeamsActivityPayload.From("29:some-teams-id", "Some User", "AAD-OBJECT-ID"),
                new TeamsActivityPayload.Conversation("conv-1", "personal"));

        controller("aad-object-id").webhook("Bearer t", activity);

        verify(agent, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).respondTo("teams-conv-1", "hello");
    }

    // -----------------------------------------------------------------------
    // Conversation type
    // -----------------------------------------------------------------------

    @Test
    void ignoresMessagesFromTheAllowedUserInAChannel() {
        ResponseEntity<Void> response = controller(ALLOWED_USER)
                .webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1", "channel"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresMessagesFromTheAllowedUserInAGroupChat() {
        ResponseEntity<Void> response = controller(ALLOWED_USER)
                .webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1", "groupChat"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    // -----------------------------------------------------------------------
    // Conversation ID and replies
    // -----------------------------------------------------------------------

    @Test
    void handsTheMessageToTheAgentAndRepliesThroughTheChannel() {
        when(agent.respondTo("teams-conv-1", "hello")).thenReturn("hi!");
        TeamsConversationReference reference = new TeamsConversationReference(SERVICE_URL, "conv-1");

        ResponseEntity<Void> response = controller(ALLOWED_USER)
                .webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1", "personal"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(channel).updateConversationReference(reference);
        verify(channelRegistry).publishMessageReceivedEvent(any(TeamsChannelMessageReceivedEvent.class));
        verify(agent, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).respondTo("teams-conv-1", "hello");
        verify(channel, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).sendMessage(reference, "hi!");
    }

    @Test
    void repliesToTheConversationTheMessageCameFromEvenWhenAnotherMessageArrivesMeanwhile() throws Exception {
        CountDownLatch secondMessageReceived = new CountDownLatch(1);
        when(agent.respondTo("teams-conv-1", "first")).thenAnswer(invocation -> {
            // Still busy with the first message while the second one comes in
            secondMessageReceived.await(WAIT_FOR_BACKGROUND_WORK_MILLIS, TimeUnit.MILLISECONDS);
            return "answer-1";
        });
        when(agent.respondTo("teams-conv-2", "second")).thenReturn("answer-2");
        TeamsWebhookController controller = controller(ALLOWED_USER);

        controller.webhook("Bearer t", activity("message", ALLOWED_USER, "first", "conv-1"));
        controller.webhook("Bearer t", activity("message", ALLOWED_USER, "second", "conv-2"));
        secondMessageReceived.countDown();

        verify(channel, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS))
                .sendMessage(new TeamsConversationReference(SERVICE_URL, "conv-1"), "answer-1");
        verify(channel, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS))
                .sendMessage(new TeamsConversationReference(SERVICE_URL, "conv-2"), "answer-2");
    }

    // -----------------------------------------------------------------------
    // Agent failures
    // -----------------------------------------------------------------------

    @Test
    void tellsTheUserWhenTheAgentFailsWithoutExposingTheError() {
        when(agent.respondTo("teams-conv-1", "hello"))
                .thenThrow(new IllegalStateException("provider said: invalid api key sk-123"));

        controller(ALLOWED_USER).webhook("Bearer t", activity("message", ALLOWED_USER, "hello", "conv-1"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(channel, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS))
                .sendMessage(eq(new TeamsConversationReference(SERVICE_URL, "conv-1")), message.capture());
        assertThat(message.getValue())
                .isEqualTo(TeamsWebhookController.ERROR_MESSAGE)
                .doesNotContain("provider said: invalid api key sk-123");
    }

    private TeamsActivityPayload activity(String type, String fromId, String text, String conversationId) {
        return activity(type, fromId, text, conversationId, "personal");
    }

    private TeamsActivityPayload activity(String type, String fromId, String text, String conversationId,
                                          String conversationType) {
        return new TeamsActivityPayload(type, "activity-1", SERVICE_URL, "msteams", text,
                new TeamsActivityPayload.From(fromId, "Some User", null),
                new TeamsActivityPayload.Conversation(conversationId, conversationType));
    }
}
