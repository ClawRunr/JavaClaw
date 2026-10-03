package ai.javaclaw.channels.teams;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamsWebhookControllerTest {

    private static final long WAIT_FOR_BACKGROUND_WORK_MILLIS = 1_000;

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private Agent agent;

    @Mock
    private TeamsChannel channel;

    @Mock
    private BotFrameworkJwtValidator jwtValidator;   // ← جدید

    private TeamsWebhookController controller(String allowedUserId) {
        TeamsProperties properties = new TeamsProperties();
        properties.setAllowedUserId(allowedUserId);
        // اکثر تست‌ها "Bearer t" می‌فرستن؛ فقط همون‌هایی که واقعاً بهش می‌رسن این stub رو مصرف می‌کنن،
        // برای همین lenient لازمه تا تستی که اصلاً auth رد نمی‌شه (بدون Bearer) با
        // UnnecessaryStubbingException fail نشه.
        lenient().when(jwtValidator.isValid(anyString())).thenReturn(true);
        return new TeamsWebhookController(properties, channelRegistry, agent, channel, jwtValidator);
    }

    @Test
    void rejectsRequestsWithoutBearerToken() {
        ResponseEntity<Void> response =
                controller(null).webhook(null, activity("message", "u1", "hello", "conv-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(agent, channel, channelRegistry, jwtValidator);
    }

    @Test
    void rejectsRequestWithInvalidJwt() {
        TeamsProperties properties = new TeamsProperties();
        when(jwtValidator.isValid("bad-token")).thenReturn(false);
        TeamsWebhookController controller =
                new TeamsWebhookController(properties, channelRegistry, agent, channel, jwtValidator);

        ResponseEntity<Void> response =
                controller.webhook("Bearer bad-token", activity("message", "u1", "hello", "conv-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void acceptsAnEmptyBody() {
        ResponseEntity<Void> response = controller(null).webhook("Bearer t", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresNonMessageActivities() {
        controller(null).webhook("Bearer t", activity("conversationUpdate", "u1", null, "conv-1"));

        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresActivitiesWithoutText() {
        controller(null).webhook("Bearer t", activity("message", "u1", null, "conv-1"));

        verifyNoInteractions(agent, channel, channelRegistry);
    }

    @Test
    void ignoresMessagesFromUnauthorizedUser() {
        controller("allowed_user").webhook("Bearer t", activity("message", "other_user", "hello", "conv-1"));

        verify(agent, never()).respondTo(anyString(), anyString());
        verifyNoInteractions(channel);
    }

    @Test
    void acceptsMessagesFromTheConfiguredAllowedUser() {
        when(agent.respondTo("conv-1", "hello")).thenReturn("hi!");

        controller("allowed_user").webhook("Bearer t", activity("message", "allowed_user", "hello", "conv-1"));

        verify(agent, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).respondTo("conv-1", "hello");
    }

    @Test
    void handsTheMessageToTheAgentAndRepliesThroughTheChannel() {
        when(agent.respondTo("conv-1", "hello")).thenReturn("hi!");

        ResponseEntity<Void> response =
                controller(null).webhook("Bearer t", activity("message", "u1", "hello", "conv-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(channel).updateConversationReference(
                new TeamsConversationReference("https://smba.example/", "conv-1"));
        verify(channelRegistry).publishMessageReceivedEvent(any(TeamsChannelMessageReceivedEvent.class));
        verify(agent, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).respondTo("conv-1", "hello");
        verify(channel, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).sendMessage("hi!");
    }

    private TeamsActivityPayload activity(String type, String fromId, String text, String conversationId) {
        return new TeamsActivityPayload(type, "activity-1", "https://smba.example/", "msteams", text,
                new TeamsActivityPayload.From(fromId, "Some User"),
                new TeamsActivityPayload.Conversation(conversationId));
    }
}