package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    final Map<String, String> edits = new ConcurrentHashMap<>();
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
        when(channel.sendMarkdown(any(TeamsConversationReference.class), anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(1, String.class));
            return Optional.of("activity-" + sent.size());
        });
        when(channel.updateMarkdown(any(TeamsConversationReference.class), anyString(), anyString())).thenAnswer(invocation -> {
            edits.put(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class));
            return true;
        });
        controller = new TeamsWebhookController(properties, new ChannelRegistry(), fixture.approvals(), channel, jwt);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedInThePersonalChatAndTheAnswerFollows() {
        controller.webhook("Bearer t", activity(OWNER, "fetch example.com", "personal"));
        await(() -> !sent.isEmpty());
        String request = sent.getFirst();
        String id = shortId(request);
        assertThat(request).startsWith("**Approval needed: run " + ChannelApprovalFixture.TOOL + "**")
                .contains("```\n  url: https://example.com\n```");
        assertThat(fixture.toolRuns()).isEmpty();

        controller.webhook("Bearer t", activity(OWNER, "/approve_" + id, "personal"));

        await(() -> sent.contains(ANSWER));
        // The request activity is updated in place with the outcome
        assertThat(edits).containsOnlyKeys("activity-1");
        assertThat(edits.get("activity-1")).contains("✅ **Approved**").doesNotContain("approve\\_");
        assertThat(sent).hasSize(2);
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void anotherUserOrAGroupChatCannotApprove() throws Exception {
        controller.webhook("Bearer t", activity(OWNER, "fetch example.com", "personal"));
        await(() -> !sent.isEmpty());
        String id = shortId(sent.getFirst());

        controller.webhook("Bearer t", activity("29:someone-else", "/approve_" + id, "personal"));
        // Personal chats only, even for the owner
        controller.webhook("Bearer t", activity(OWNER, "/approve_" + id, "groupChat"));

        Thread.sleep(300);
        assertThat(edits).isEmpty();
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("teams-conv-1")).isNotEmpty();

        controller.webhook("Bearer t", activity(OWNER, "/deny " + id, "personal"));
        await(() -> !edits.isEmpty());
        assertThat(edits.values()).singleElement().asString().contains("❌ **Denied**");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    private static TeamsActivityPayload activity(String fromId, String text, String conversationType) {
        return new TeamsActivityPayload("message", "activity-1", SERVICE_URL, "msteams", text,
                new TeamsActivityPayload.From(fromId, "Some User", null),
                new TeamsActivityPayload.Conversation("conv-1", conversationType));
    }
}
