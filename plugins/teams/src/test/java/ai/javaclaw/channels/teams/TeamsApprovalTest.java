package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a Teams turn is approved in the same personal chat: the request arrives as a
 * message, {@code /approve <id>} runs the tool, and the answer follows. Real agent, real gate,
 * real webhook controller; only the Bot Framework is faked.
 */
class TeamsApprovalTest {

    static final String SERVICE_URL = "https://smba.example/";
    static final String OWNER = "29:allowed-user";

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<String> sent = new CopyOnWriteArrayList<>();
    TeamsWebhookController controller;

    @BeforeEach
    void setUp() {
        TeamsProperties properties = new TeamsProperties();
        properties.setAllowedUserId(OWNER);
        BotFrameworkJwtValidator jwt = mock(BotFrameworkJwtValidator.class);
        when(jwt.isValid("t", SERVICE_URL)).thenReturn(true);
        TeamsChannel channel = mock(TeamsChannel.class);
        when(channel.getName()).thenReturn(TeamsChannel.CHANNEL_ID);
        doAnswer(invocation -> sent.add(invocation.getArgument(1, String.class)))
                .when(channel).sendMessage(any(TeamsConversationReference.class), anyString());
        controller = new TeamsWebhookController(properties, new ChannelRegistry(), fixture.approvals(), channel, jwt);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedInThePersonalChatAndTheAnswerFollows() {
        controller.webhook("Bearer t", activity(OWNER, "fetch example.com", "personal"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String request = sent.getFirst();
        assertThat(request).contains(ChannelApprovalFixture.TOOL).contains(ARGUMENTS);
        assertThat(fixture.toolRuns()).isEmpty();

        controller.webhook("Bearer t", activity(OWNER, "/approve " + shortId(request), "personal"));

        await(() -> sent.contains(ANSWER));
        assertThat(sent).anyMatch(m -> m.startsWith("Approved " + shortId(request)));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void anotherUserOrAGroupChatCannotApprove() throws Exception {
        controller.webhook("Bearer t", activity(OWNER, "fetch example.com", "personal"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String id = shortId(sent.getFirst());

        controller.webhook("Bearer t", activity("29:someone-else", "/approve " + id, "personal"));
        // Personal chats only, even for the owner
        controller.webhook("Bearer t", activity(OWNER, "/approve " + id, "groupChat"));

        Thread.sleep(300);
        assertThat(sent).noneMatch(m -> m.startsWith("Approved"));
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("teams-conv-1")).isNotEmpty();

        controller.webhook("Bearer t", activity(OWNER, "/deny " + id, "personal"));
        await(() -> sent.stream().anyMatch(m -> m.startsWith("Denied " + id)));
        assertThat(fixture.toolRuns()).isEmpty();
    }

    private static TeamsActivityPayload activity(String fromId, String text, String conversationType) {
        return new TeamsActivityPayload("message", "activity-1", SERVICE_URL, "msteams", text,
                new TeamsActivityPayload.From(fromId, "Some User", null),
                new TeamsActivityPayload.Conversation("conv-1", conversationType));
    }
}
