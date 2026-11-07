package ai.javaclaw.channels;

import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.TOOL;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text approval flow messaging channels share, through a real agent and the real gate: the
 * owner gets the exact call with a short id, answers {@code /approve} or {@code /deny} in the same
 * chat, and the turn carries on.
 */
class ChannelApprovalsTest {

    static final String CHAT = "telegram-42";

    ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    final List<String> replies = new CopyOnWriteArrayList<>();
    final List<RuntimeException> failures = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void theRequestMessageNamesTheCallItsRiskAndAShortIdToAnswerWith() throws Exception {
        String request = askAndAwaitRequest(CHAT);

        assertThat(request).startsWith("JavaClaw wants to run " + TOOL + ": " + ARGUMENTS + "\n");
        assertThat(request).contains("Risk: NETWORK");
        String id = shortId(request);
        assertThat(id).hasSize(6);
        assertThat(request).endsWith("Reply /approve " + id + " or /deny " + id + " (expires in 30 seconds).");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void longArgumentsAreTruncatedAndSaySo() throws Exception {
        fixture.close();
        String longArguments = "{\"url\":\"https://example.com/" + "x".repeat(2_000) + "\"}";
        fixture = new ChannelApprovalFixture(Duration.ofSeconds(30), longArguments);

        String request = askAndAwaitRequest(CHAT);

        assertThat(request).doesNotContain(longArguments)
                .contains("(arguments truncated: showing 600 of " + longArguments.length() + " characters)");
        assertThat(request.length()).isLessThan(1_000);
    }

    @Test
    void approveRunsTheToolOnceAndNeitherCommandReachesTheModel() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, "/approve " + id)).isTrue();

        assertThat(next()).isEqualTo("Approved " + id + ": running " + TOOL + ".");
        assertThat(next()).isEqualTo(ANSWER);
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);

        // Used: a second answer changes nothing
        assertThat(command(CHAT, "/approve " + id)).isTrue();
        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting");
        assertThat(fixture.toolRuns()).hasSize(1);
        assertThat(fixture.modelSaw()).isNotEmpty().noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void denyNeverRunsTheToolAndTheModelHearsWhy() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, "/deny " + id)).isTrue();

        assertThat(next()).isEqualTo("Denied " + id + ": " + TOOL + " was not run.");
        assertThat(next()).startsWith("Not done: Tool '" + TOOL + "' was NOT run").contains("the user denied it");
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/deny"));
    }

    @Test
    void anUnknownIdHasNoEffectAndGetsAReply() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, "/approve zzzzzz")).isTrue();

        assertThat(replies).singleElement().asString().contains("No approval zzzzzz is waiting");
        assertThat(sent.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor(CHAT)).isNotEmpty();

        command(CHAT, "/deny " + id);
        next();
    }

    @Test
    void anIdFromAnotherConversationHasNoEffectThereAndGetsAReply() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command("discord-7", "/approve " + id)).isTrue();

        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting in this chat");
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor(CHAT)).isNotEmpty();

        // Still answerable where it was asked
        command(CHAT, "/approve " + id);
        assertThat(next()).startsWith("Approved");
        assertThat(next()).isEqualTo(ANSWER);
    }

    @Test
    void anExpiredApprovalSaysSoAndCanNoLongerBeAnswered() throws Exception {
        fixture.close();
        fixture = new ChannelApprovalFixture(Duration.ofSeconds(1), ARGUMENTS);
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(next()).isEqualTo("Expired " + id + ": " + TOOL + " was not run because nobody approved it within 1 second.");
        assertThat(next()).startsWith("Not done:");

        assertThat(command(CHAT, "/approve " + id)).isTrue();
        assertThat(replies).singleElement().asString().contains("No approval " + id + " is waiting");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void ordinaryMessagesAreNotCommandsAndACommandWithoutAnIdExplainsItself() {
        assertThat(command(CHAT, "please approve the plan")).isFalse();
        assertThat(command(CHAT, "/approvement")).isFalse();
        assertThat(replies).isEmpty();

        assertThat(command(CHAT, "/approve")).isTrue();
        assertThat(replies).singleElement().asString().contains("/approve <id>");
    }

    @Test
    void telegramsBotSuffixAndCapitalsAreUnderstood() throws Exception {
        String id = shortId(askAndAwaitRequest(CHAT));

        assertThat(command(CHAT, "/Approve@JavaClawBot " + id.toUpperCase())).isTrue();

        assertThat(next()).startsWith("Approved " + id);
        assertThat(next()).isEqualTo(ANSWER);
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
            approvals.respond(CHAT, "hi", sent::add, failures::add);

            ChannelApprovalFixture.await(() -> !failures.isEmpty());
            assertThat(failures).singleElement().extracting(Throwable::getMessage).isEqualTo("provider down");
            assertThat(sent).isEmpty();
        } finally {
            approvals.close();
        }
    }

    private String askAndAwaitRequest(String conversationId) throws InterruptedException {
        fixture.approvals().respond(conversationId, "fetch example.com", sent::add, failures::add);
        String request = next();
        assertThat(request).as("approval request").contains("wants to run");
        return request;
    }

    private boolean command(String conversationId, String text) {
        return fixture.approvals().handleCommand(conversationId, text, replies::add);
    }

    private String next() throws InterruptedException {
        String message = sent.poll(10, TimeUnit.SECONDS);
        assertThat(message).as("next message to the channel (failures: %s)", failures).isNotNull();
        return message;
    }
}
