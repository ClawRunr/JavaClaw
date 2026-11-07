package ai.javaclaw.channels;

import ai.javaclaw.testsupport.ChannelApprovalFixture;
import ai.javaclaw.testsupport.RecordingChat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.TOOL;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text approval flow messaging channels share, through a real agent and the real gate: the
 * owner gets the call with a short id, answers {@code /approve_<id>} or {@code /deny_<id>} in the
 * same chat, and the turn carries on. How the request reads per tool is {@link ApprovalMessagesTest}.
 */
class ChannelApprovalsTest {

    static final String CHAT = "telegram-42";

    ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    RecordingChat chat = new RecordingChat(ApprovalFormat.TELEGRAM_HTML, true);
    final List<String> replies = new CopyOnWriteArrayList<>();
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void theRequestHasATitleTheArgumentsAnExpiryTimeAndOneTapCommands() {
        String request = askAndAwaitRequest(CHAT);

        String id = shortId(request);
        assertThat(id).hasSize(6);
        assertThat(request).startsWith("<b>Approval needed: run " + TOOL + "</b>\n")
                .contains("<pre>  url: https://example.com</pre>")
                .contains("Risk: NETWORK")
                .containsPattern("Expires at \\d\\d:\\d\\d")
                .endsWith("Reply /approve_" + id + " or /deny_" + id);
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/approve_%s", "/approve %s", "/Approve_%s@JavaClawBot", "/approve@JavaClawBot %s"})
    void approveRunsTheToolOnceAndNeitherCommandReachesTheModel(String command) {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, command.formatted(id))).isTrue();

        await(() -> chat.sent().contains(ANSWER));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).isNotEmpty().noneMatch(text -> text.contains("/approve"));

        // Used: a second answer changes nothing
        assertThat(command(CHAT, "/approve_" + id)).isTrue();
        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting");
        assertThat(fixture.toolRuns()).hasSize(1);
    }

    @Test
    void theOutcomeReplacesTheCommandsInTheOriginalMessageWhereTheChannelCanEdit() {
        String id = shortId(askAndAwaitRequest(CHAT));

        command(CHAT, "/approve_" + id);

        await(() -> chat.sent().contains(ANSWER));
        // Request, then the answer: no separate outcome message
        assertThat(chat.sent()).hasSize(2);
        assertThat(chat.edits()).singleElement().asString()
                .startsWith("<b>Approval needed: run " + TOOL + "</b>")
                .contains("✅ <b>Approved</b>: running " + TOOL)
                .doesNotContain("/approve_").doesNotContain("Expires at");
        assertThat(chat.formatted(0)).isEqualTo(chat.edits().getFirst());
    }

    @Test
    void whereTheChannelCannotEditTheOutcomeIsAShortReply() {
        chat = new RecordingChat(ApprovalFormat.WHATSAPP, false);
        String id = shortId(askAndAwaitRequest(CHAT));

        command(CHAT, "/deny_" + id);

        await(() -> chat.sent().size() == 3);
        assertThat(chat.edits()).isEmpty();
        assertThat(chat.sent().get(1)).isEqualTo("❌ *Denied*: " + TOOL + " was not run. (" + id + ")");
        assertThat(chat.sent().get(2)).startsWith("Not done: Tool '" + TOOL + "' was NOT run").contains("the user denied it");
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/deny"));
    }

    @Test
    void anUnknownIdHasNoEffectAndGetsAReply() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, "/approve_zzzzzz")).isTrue();

        assertThat(replies).singleElement().asString().contains("No approval zzzzzz is waiting");
        Thread.sleep(200);
        assertThat(chat.sent()).hasSize(1);
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor(CHAT)).isNotEmpty();

        command(CHAT, "/deny_" + id);
        await(() -> chat.sent().size() == 2);
    }

    @Test
    void anIdFromAnotherConversationHasNoEffectThereAndGetsAReply() {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command("discord-7", "/approve_" + id)).isTrue();

        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting in this chat");
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor(CHAT)).isNotEmpty();

        // Still answerable where it was asked
        command(CHAT, "/approve_" + id);
        await(() -> chat.sent().contains(ANSWER));
    }

    @Test
    void anExpiredRequestIsEditedToSaySoAndCanNoLongerBeAnswered() {
        fixture.close();
        fixture = new ChannelApprovalFixture(Duration.ofSeconds(1), ARGUMENTS);
        String id = shortId(askAndAwaitRequest(CHAT));

        await(() -> !chat.edits().isEmpty());
        assertThat(chat.edits().getFirst())
                .contains("⌛ <b>Expired</b>: " + TOOL + " was not run because nobody approved it within 1 second.");
        await(() -> chat.sent().size() == 2);
        assertThat(chat.sent().get(1)).startsWith("Not done:");

        assertThat(command(CHAT, "/approve_" + id)).isTrue();
        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void ordinaryMessagesAreNotCommandsAndACommandWithoutAnIdExplainsItself() {
        assertThat(command(CHAT, "please approve the plan")).isFalse();
        assertThat(command(CHAT, "/approvement")).isFalse();
        assertThat(replies).isEmpty();

        assertThat(command(CHAT, "/approve")).isTrue();
        assertThat(replies).singleElement().asString().contains("/approve_<id>");
    }

    @Test
    void aFailingTurnIsReportedInsteadOfAnswered() {
        ChannelApprovals approvals = new ChannelApprovals(new ai.javaclaw.agent.Agent() {
            @Override
            public String respondTo(String conversationId, String question) {
                throw new IllegalStateException("provider down");
            }

            @Override
            public <T> T prompt(String conversationId, String input, Class<T> result) {
                throw new UnsupportedOperationException();
            }
        }, fixture.pending());
        try {
            approvals.respond(CHAT, "hi", chat, failures::add);

            await(() -> !failures.isEmpty());
            assertThat(failures).singleElement().extracting(Throwable::getMessage).isEqualTo("provider down");
            assertThat(chat.sent()).isEmpty();
        } finally {
            approvals.close();
        }
    }

    private String askAndAwaitRequest(String conversationId) {
        fixture.approvals().respond(conversationId, "fetch example.com", chat, failures::add);
        await(() -> !chat.sent().isEmpty() || !failures.isEmpty());
        assertThat(failures).isEmpty();
        String request = chat.sent().getFirst();
        assertThat(request).as("approval request").contains("Approval needed");
        return request;
    }

    private boolean command(String conversationId, String text) {
        return fixture.approvals().handleCommand(conversationId, text, replies::add);
    }
}
