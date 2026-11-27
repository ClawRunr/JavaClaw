package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTest {

    private static final String CHAT_JID = "1234567890@s.whatsapp.net";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The exact shape of 'wacli send --json' output: the ID is nested under "data". */
    private static final String SEND_OUTPUT = """
            {
              "success": true,
              "data": { "id": "msg-1", "sent": true, "to": "95318997741682@lid" },
              "error": null
            }
            """;

    @Mock
    private CliRunner cliRunner;

    @Mock
    private WacliSync sync;

    private WhatsApp whatsApp;
    private final List<String> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        whatsApp = new WhatsApp(CHAT_JID, cliRunner, sync);
        whatsApp.registerMessageReceiver((fromJidId, message) -> received.add(fromJidId + ": " + message));
    }

    /** Makes every wacli command return the given result. */
    private void wacliReturns(CliResult result) throws Exception {
        when(cliRunner.run(anyList(), any(Duration.class))).thenReturn(result);
    }

    private void givenConnected() {
        when(sync.isRunning()).thenReturn(true);
    }

    private static final String TIMESTAMP = "2024-01-03T00:00:00Z";

    /** A message from the JID we are configured for, which is what we answer. */
    private static WacliWebhookPayload payload(String id, String text) {
        return payload(id, text, CHAT_JID, false);
    }

    private static WacliWebhookPayload payload(String id, String text, String senderJid, boolean fromMe) {
        return new WacliWebhookPayload(CHAT_JID, id, senderJid, fromMe, text, "Tester", TIMESTAMP);
    }

    @Test
    void isInstalledAndIsPairedRunTheirWacliCommands() throws Exception {
        wacliReturns(new CliResult(0, "", ""));

        assertThat(whatsApp.isInstalled()).isTrue();
        assertThat(whatsApp.isPaired()).isTrue();

        verify(cliRunner).run(List.of("wacli", "version"), TIMEOUT);
        verify(cliRunner).run(List.of("wacli", "auth", "status", "--json"), TIMEOUT);
    }

    @Test
    void isInstalledIsFalseWhenTheBinaryCannotBeRun() throws Exception {
        when(cliRunner.run(anyList(), any(Duration.class))).thenThrow(new IOException("no such file"));

        assertThat(whatsApp.isInstalled()).isFalse();
    }

    @Test
    void startDoesNotConnectWhenWacliIsNotInstalled() throws Exception {
        wacliReturns(new CliResult(1, "", ""));

        assertThat(whatsApp.start()).isFalse();
        verify(sync, never()).start();
    }

    @Test
    void startConnectsWhenWacliIsInstalled() throws Exception {
        wacliReturns(new CliResult(0, "", ""));

        assertThat(whatsApp.start()).isTrue();
        verify(sync).start();
    }

    @Test
    void sendsTextWithTheExpectedWacliArguments() throws Exception {
        givenConnected();
        wacliReturns(new CliResult(0, SEND_OUTPUT, ""));

        whatsApp.sendMessage("hi there");

        verify(cliRunner).run(List.of(
                "wacli", "send", "text",
                "--to", CHAT_JID,
                "--message", "hi there",
                "--json"), TIMEOUT);
    }

    @Test
    void doesNotSendWhenNotConnected() throws Exception {
        whatsApp.sendMessage("hi there");

        verify(cliRunner, never()).run(anyList(), any(Duration.class));
    }

    @Test
    void doesNotSendBlankMessages() throws Exception {
        whatsApp.sendMessage("   ");
        whatsApp.sendMessage(null);

        verify(cliRunner, never()).run(anyList(), any(Duration.class));
    }

    @Test
    void deliversIncomingMessagesToEveryReceiver() {
        whatsApp.registerMessageReceiver((fromJidId, message) -> received.add("second: " + message));

        whatsApp.onIncomingMessage(payload("msg-1", "hello"));

        assertThat(received).containsExactly(CHAT_JID + ": hello", "second: hello");
    }

    @Test
    void ignoresTheEchoOfAMessageItSent() throws Exception {
        givenConnected();
        wacliReturns(new CliResult(0, SEND_OUTPUT, ""));
        whatsApp.sendMessage("my reply");

        whatsApp.onIncomingMessage(payload("msg-1", "my reply"));
        whatsApp.onIncomingMessage(payload("msg-2", "and this one is a human"));

        assertThat(received).containsExactly(CHAT_JID + ": and this one is a human");
    }

    @Test
    void cannotIgnoreTheEchoWhenWacliReportsNoMessageId() throws Exception {
        givenConnected();
        wacliReturns(new CliResult(0, "not json at all", ""));
        whatsApp.sendMessage("my reply");

        whatsApp.onIncomingMessage(payload("msg-1", "my reply"));

        assertThat(received).containsExactly(CHAT_JID + ": my reply");
    }

    @Test
    void listsOnlyOneToOneChats() throws Exception {
        wacliReturns(new CliResult(0, """
                { "success": true, "error": null, "data": [
                  { "jid": "1@s.whatsapp.net", "kind": "dm",         "name": "Alice" },
                  { "jid": "2@g.us",           "kind": "group",      "name": "Family" },
                  { "jid": "3@newsletter",     "kind": "newsletter", "name": "News" },
                  { "jid": "4@broadcast",      "kind": "unknown",    "name": "List" },
                  { "jid": "5@lid",            "kind": "dm",         "name": "Bob" }
                ]}
                """, ""));

        assertThat(whatsApp.oneToOneChats())
                .containsExactly(new WhatsApp.Chat("1@s.whatsapp.net", "Alice"), new WhatsApp.Chat("5@lid", "Bob"));
        verify(cliRunner).run(List.of("wacli", "chats", "list", "--json", "--limit", "200"), TIMEOUT);
    }

    @Test
    void fallsBackToTheJidWhenAChatHasNoName() throws Exception {
        wacliReturns(new CliResult(0, """
                { "data": [ { "jid": "1@lid", "kind": "dm", "name": "" } ] }
                """, ""));

        assertThat(whatsApp.oneToOneChats()).containsExactly(new WhatsApp.Chat("1@lid", "1@lid"));
    }

    @Test
    void listsNoChatsWhenWacliFails() throws Exception {
        wacliReturns(new CliResult(1, "", "not paired"));

        assertThat(whatsApp.oneToOneChats()).isEmpty();
    }

    @Test
    void ignoresMessagesFromAnotherSender() {
        whatsApp.onIncomingMessage(payload("msg-1", "hello", "999@s.whatsapp.net", false));

        assertThat(received).isEmpty();
    }

    @Test
    void ignoresAMessageInOurChatTypedBySomebodyElse() {
        // The chat is ours but the sender is not, which is what authorising by sender buys: in a
        // group, only the configured person can instruct the agent.
        whatsApp.onIncomingMessage(payload("msg-1", "run rm -rf /", "999@s.whatsapp.net", false));

        assertThat(received).isEmpty();
    }

    @Test
    void ignoresAMessageWithNoSender() {
        whatsApp.onIncomingMessage(payload("msg-1", "hello", null, false));

        assertThat(received).isEmpty();
    }

    @Test
    void answersWhatYouTypeInYourOwnSelfChat() {
        // In "Message Yourself" what you type carries FromMe=true, exactly as our own replies do.
        // That is why echoes are recognised by message ID and not by that flag.
        whatsApp.onIncomingMessage(payload("msg-9", "remind me at 6", CHAT_JID, true));

        assertThat(received).containsExactly(CHAT_JID + ": remind me at 6");
    }

    @Test
    void stopDisconnects() {
        whatsApp.stop();

        verify(sync).stop();
    }

    @Test
    void ignoresEverythingWhenNoChatIsConfigured() {
        WhatsApp unconfigured = new WhatsApp(null, cliRunner, sync);
        unconfigured.registerMessageReceiver((fromJidId, message) -> received.add(message));

        unconfigured.onIncomingMessage(payload("msg-1", "hello"));

        assertThat(received).isEmpty();
    }

    @Test
    void ignoresEmptyAndTextlessPayloads() {
        whatsApp.onIncomingMessage(null);
        whatsApp.onIncomingMessage(payload("msg-1", null));
        whatsApp.onIncomingMessage(payload("msg-2", "   "));

        assertThat(received).isEmpty();
    }
}
