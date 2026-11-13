package ai.javaclaw.channels;

import ai.javaclaw.testsupport.ChannelApprovalFixture;
import ai.javaclaw.testsupport.RecordingChat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.TOOL;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text approval flow messaging channels share, through a real agent and the real policy: the
 * turn pauses, the owner gets the call with a short id, answers {@code /approve_<id>} or
 * {@code /deny_<id>} in the same chat, and the turn carries on. How the request reads per tool is {@link ApprovalMessagesTest}.
 */
class ChannelApprovalsTest {

    static final String CHAT = "telegram-42";

    ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    RecordingChat chat = new RecordingChat(ApprovalFormat.TELEGRAM_HTML);
    final List<String> replies = new CopyOnWriteArrayList<>();
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void theRequestHasATitleTheArgumentsAndOneTapCommands() {
        String request = askAndAwaitRequest(CHAT);

        String id = shortId(request);
        assertThat(id).hasSize(6);
        assertThat(request).startsWith("<b>JavaClaw wants to run</b> <code>" + TOOL + "</code>\n")
                .contains("<pre>  url: https://example.com</pre>")
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
    void theOutcomeIsAShortReply() {
        String id = shortId(askAndAwaitRequest(CHAT));

        command(CHAT, "/approve_" + id);

        await(() -> chat.sent().contains(ANSWER));
        assertThat(chat.sent()).containsExactly(chat.sent().getFirst(),
                "✅ Approved: running " + TOOL + ". (" + id + ")", ANSWER);
    }

    @Test
    void denyDoesNotRunTheTool() {
        chat = new RecordingChat(ApprovalFormat.WHATSAPP);
        String id = shortId(askAndAwaitRequest(CHAT));

        command(CHAT, "/deny_" + id);

        await(() -> chat.sent().size() == 3);
        assertThat(chat.sent().get(1)).isEqualTo("❌ The user did not approve this action. (" + id + ")");
        assertThat(chat.sent().get(2)).isEqualTo("Not done: The user did not approve this action.");
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
        assertThat(fixture.agent().waitingForApproval(CHAT)).isNotEmpty();

        command(CHAT, "/deny_" + id);
        await(() -> chat.sent().size() == 3);
    }

    @Test
    void anIdFromAnotherConversationHasNoEffectThereAndGetsAReply() {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command("discord-7", "/approve_" + id)).isTrue();

        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting in this chat");
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.agent().waitingForApproval(CHAT)).isNotEmpty();

        // Still answerable where it was asked
        command(CHAT, "/approve_" + id);
        await(() -> chat.sent().contains(ANSWER));
    }

    @Test
    void aNewMessageWhileARequestWaitsDeniesItAndTheOldIdNoLongerWorks() {
        String id = shortId(askAndAwaitRequest(CHAT));

        fixture.approvals().respond(CHAT, "never mind", chat, failures::add);

        await(() -> chat.sent().size() == 3);
        assertThat(chat.sent().get(1)).isEqualTo("❌ The user moved on without approving. (" + id + ")");
        assertThat(chat.sent().get(2)).isEqualTo("Not done: The user moved on without approving.");
        assertThat(command(CHAT, "/approve_" + id)).isTrue();
        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void aChatOthersCanReadIsNeverAskedAndTheTurnFinishes() {
        RecordingChat group = new RecordingChat(ApprovalFormat.TELEGRAM_HTML, false);

        fixture.approvals().respond("telegram-group", "fetch example.com", group, failures::add);

        await(() -> !group.sent().isEmpty());
        assertThat(group.sent()).containsExactly(
                "Not done: No one could be asked to approve this action here, so it was not run.");
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
        ChannelApprovals approvals = ChannelApprovalFixture.onCallingThread(new ai.javaclaw.agent.Agent() {
            @Override
            public String respondTo(String conversationId, String question) {
                throw new IllegalStateException("provider down");
            }

            @Override
            public <T> T prompt(String conversationId, String input, Class<T> result) {
                throw new UnsupportedOperationException();
            }
        });
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
        assertThat(request).as("approval request").contains("JavaClaw wants to run");
        return request;
    }

    private boolean command(String conversationId, String text) {
        return fixture.approvals().handleCommand(conversationId, text, replies::add);
    }
}
