package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a WhatsApp turn is approved in the same chat, through the real wacli message
 * path ({@link WhatsApp#onIncomingMessage}, which drops other chats): the request is sent with
 * {@code wacli send}, {@code /approve <id>} runs the tool, and the answer follows. Real agent, real
 * gate; only the wacli binary is faked.
 */
class WhatsAppApprovalTest {

    static final String CHAT_JID = "1234567890@s.whatsapp.net";

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger ids = new AtomicInteger();
    WhatsApp whatsApp;

    @BeforeEach
    void setUp() throws Exception {
        CliRunner wacli = mock(CliRunner.class);
        when(wacli.run(anyList(), any(Duration.class))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            sent.add(command.get(command.indexOf("--message") + 1));
            return new CliResult(0, "{\"success\":true,\"data\":{\"id\":\"sent-" + ids.incrementAndGet() + "\"}}", "");
        });
        WacliSync sync = mock(WacliSync.class);
        when(sync.isRunning()).thenReturn(true);
        whatsApp = new WhatsApp(CHAT_JID, wacli, sync);
        WhatsAppChannel channel = new WhatsAppChannel(whatsApp, new ChannelRegistry(), fixture.approvals());
        whatsApp.registerMessageReceiver(channel::onMessage);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedInTheChatAndTheAnswerFollows() {
        receive(CHAT_JID, "fetch example.com");
        await(() -> !sent.isEmpty());
        String request = sent.getFirst();
        String id = shortId(request);
        assertThat(request).startsWith("*JavaClaw wants to run* ```" + ChannelApprovalFixture.TOOL + "```")
                .contains("```  url: https://example.com```")
                .endsWith("Reply /approve_" + id + " or /deny_" + id);
        assertThat(fixture.toolRuns()).isEmpty();

        receive(CHAT_JID, "/approve_" + id);

        await(() -> sent.contains(ANSWER));
        // The outcome is a short reply between the request and the answer
        assertThat(sent).containsExactly(request, "✅ Approved: running " + ChannelApprovalFixture.TOOL + ". (" + id + ")", ANSWER);
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void someoneElseCannotApprove() throws Exception {
        receive(CHAT_JID, "fetch example.com");
        await(() -> !sent.isEmpty());
        String id = shortId(sent.getFirst());

        receive("999@s.whatsapp.net", "/approve_" + id);

        Thread.sleep(300);
        assertThat(sent).hasSize(1);
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor(CHAT_JID)).isNotEmpty();

        receive(CHAT_JID, "/deny " + id);
        await(() -> sent.stream().anyMatch(m -> m.startsWith("❌ The user did not approve this action.")));
        assertThat(fixture.toolRuns()).isEmpty();
    }

    private final AtomicInteger incoming = new AtomicInteger();

    private void receive(String senderJid, String text) {
        whatsApp.onIncomingMessage(new WacliWebhookPayload(CHAT_JID, "in-" + incoming.incrementAndGet(),
                senderJid, false, text, "Tester", "2024-01-03T00:00:00Z"));
    }
}
