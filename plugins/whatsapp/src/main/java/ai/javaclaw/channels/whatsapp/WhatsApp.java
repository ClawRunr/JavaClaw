package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one WhatsApp chat the assistant talks in: {@link #sendMessage} to send text there,
 * {@link #registerMessageReceiver} to be told about text arriving there. Messages in any other
 * chat are ignored.
 *
 * <p>It works by driving <a href="https://github.com/openclaw/wacli">wacli</a>, a small command
 * line tool that pairs as a WhatsApp Web linked device. {@link #start} leaves {@code wacli sync}
 * running in the background, wacli posts every incoming message to a local webhook, and
 * {@link #onIncomingMessage} hands it to the receivers. This is the only class that runs wacli.
 */
public class WhatsApp {

    @FunctionalInterface
    public interface MessageReceiver {
        void onMessage(String fromJidId, String message);
    }

    static final String INSTALL_HINT = "Install it (macOS: 'brew install openclaw/tap/wacli', "
            + "Linux: 'go install github.com/openclaw/wacli@latest').";

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsApp.class);

    /** The wacli binary, taken from the PATH. */
    private static final String WACLI = "wacli";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    /** Returned when a wacli command could not be run at all: it is missing, or it took too long. */
    private static final CliResult COMMAND_FAILED = new CliResult(-1, "", "");

    /** How many of our own message IDs to remember, so we recognise wacli's echo of our replies. */
    private static final int REMEMBERED_SENT_IDS = 256;

    /** wacli's name for a one-to-one chat, as opposed to a group, newsletter or broadcast. */
    private static final String DIRECT_CHAT_KIND = "dm";

    private static final int MAX_CHATS_LISTED = 200;

    /**
     * JID of the chat we talk in: a contact's number followed by {@code @s.whatsapp.net}, a group
     * followed by {@code @g.us}, or a {@code @lid}.
     */
    private final String chatJid;

    private final CliRunner cliRunner;
    private final WacliSync sync;
    private final List<MessageReceiver> messageReceivers = new CopyOnWriteArrayList<>();
    private final Queue<String> sentMessageIds = new ConcurrentLinkedQueue<>();

    public WhatsApp(String chatJid, CliRunner cliRunner, String webhookUrl) {
        this.chatJid = chatJid;
        this.cliRunner = cliRunner;
        this.sync = new WacliSync(() -> startSyncProcess(webhookUrl));
    }

    WhatsApp(String chatJid, CliRunner cliRunner, WacliSync sync) {
        this.chatJid = chatJid;
        this.cliRunner = cliRunner;
        this.sync = sync;
    }

    /** @return {@code true} if the wacli command line is present and can be run */
    public boolean isInstalled() {
        return runWacli("version").isSuccess();
    }

    /** @return {@code true} if wacli is paired with a phone as a WhatsApp Web linked device */
    public boolean isPaired() {
        return runWacli("auth", "status", "--json").isSuccess();
    }

    /**
     * Connects to WhatsApp by leaving {@code wacli sync} running in the background.
     *
     * @return {@code true} if the connection was started, {@code false} if wacli is not installed
     */
    public boolean start() {
        if (!isInstalled()) {
            LOGGER.error("The wacli command line was not found, so the WhatsApp channel is disabled. {}", INSTALL_HINT);
            return false;
        }
        sync.start();
        return true;
    }

    /** Disconnects from WhatsApp. */
    public void stop() {
        sync.stop();
    }

    /** @return {@code true} while messages can be received and replies sent */
    public boolean isConnected() {
        return sync.isRunning();
    }

    /** Sends a text message to our chat. */
    public void sendMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!isConnected()) {
            LOGGER.warn("WhatsApp is not connected, cannot send message '{}'", message);
            return;
        }

        CliResult result = runWacli("send", "text", "--to", chatJid, "--message", message, "--json");
        if (!result.isSuccess()) {
            LOGGER.error("'wacli send' exited with code {}: {}", result.exitCode(), result.stderr().strip());
            return;
        }
        rememberSentMessage(sentMessageId(result.stdout()));
    }

    /**
     * Lists the one-to-one chats wacli knows about, most recent first, so onboarding can offer
     * them instead of asking for a raw JID. Groups, newsletters and broadcasts are left out on
     * purpose: we authorise by chat, and those have more than one person who could talk to us.
     *
     * @return the chats, or an empty list if wacli is unavailable or has synced nothing yet
     */
    public List<Chat> oneToOneChats() {
        CliResult result = runWacli("chats", "list", "--json", "--limit", String.valueOf(MAX_CHATS_LISTED));
        if (!result.isSuccess()) {
            LOGGER.warn("Could not list WhatsApp chats: {}", result.stderr().strip());
            return List.of();
        }
        return oneToOneChatsIn(result.stdout());
    }

    /** A chat the assistant could be pointed at. */
    public record Chat(String jid, String name) {
    }

    /** Registers a receiver to be told about every message arriving in our chat. */
    public void registerMessageReceiver(MessageReceiver messageReceiver) {
        messageReceivers.add(messageReceiver);
    }

    /** Called by {@link WhatsAppWebhookController} for every message wacli posts to us. */
    void onIncomingMessage(WacliWebhookPayload payload) {
        if (payload == null || payload.text() == null || payload.text().isBlank()) {
            return;
        }
        if (!isOurChat(payload.senderJid())) {
            LOGGER.warn("Ignoring WhatsApp message from chat '{}', we only answer in '{}'", payload.chat(), chatJid);
            return;
        }
        if (weSentThisOurselves(payload.id())) {
            // 'wacli sync' echoes our own replies back to us. We recognise them by message ID
            // rather than by FromMe, because that is what lets you chat with the assistant in your
            // own "Message Yourself" chat, where what you type also arrives with FromMe=true.
            return;
        }
        for (MessageReceiver messageReceiver : messageReceivers) {
            messageReceiver.onMessage(payload.chat(), payload.text());
        }
    }

    private boolean isOurChat(String incomingSenderJid) {
        if (chatJid == null || chatJid.isBlank() || incomingSenderJid == null) {
            return false;
        }
        return chatJid.trim().equalsIgnoreCase(incomingSenderJid.trim());
    }

    private void rememberSentMessage(String messageId) {
        if (messageId == null) {
            LOGGER.debug("The message we just sent has no ID, so we may end up answering its echo");
            return;
        }
        sentMessageIds.add(messageId);
        if (sentMessageIds.size() > REMEMBERED_SENT_IDS) {
            sentMessageIds.poll();
        }
    }

    private boolean weSentThisOurselves(String messageId) {
        return messageId != null && sentMessageIds.contains(messageId);
    }

    // ----------------------------------------------------------------------------------------
    // Running wacli
    // ----------------------------------------------------------------------------------------

    /**
     * Starts the long-running {@code wacli sync} process. Its own output is dropped -- we read the
     * messages from the webhook instead -- but its errors are left visible on the console.
     */
    private Process startSyncProcess(String webhookUrl) throws IOException {
        List<String> command = wacliCommand("sync", "--follow", "--webhook", webhookUrl, "--webhook-allow-private");
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private CliResult runWacli(String... arguments) {
        List<String> command = wacliCommand(arguments);
        try {
            return cliRunner.run(command, COMMAND_TIMEOUT);
        } catch (IOException e) {
            LOGGER.debug("Could not run {}", command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return COMMAND_FAILED;
    }

    private static List<String> wacliCommand(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(WACLI);
        command.addAll(List.of(arguments));
        return command;
    }

    /** Reads the one-to-one chats out of the JSON that {@code wacli chats list --json} prints. */
    private static List<Chat> oneToOneChatsIn(String listOutput) {
        try {
            ChatsResponse response = JSON_MAPPER.readValue(listOutput, ChatsResponse.class);
            if (response.data() == null) {
                return List.of();
            }
            return response.data().stream()
                    .filter(row -> DIRECT_CHAT_KIND.equals(row.kind()) && row.jid() != null)
                    .map(row -> new Chat(row.jid(), row.name() == null || row.name().isBlank() ? row.jid() : row.name()))
                    .toList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the output of 'wacli chats list'", e);
            return List.of();
        }
    }

    private record ChatsResponse(List<Row> data) {
        private record Row(String jid, String kind, String name) {
        }
    }

    /** Reads the message ID out of the JSON that {@code wacli send --json} prints. */
    private static String sentMessageId(String sendOutput) {
        try {
            SendResponse response = JSON_MAPPER.readValue(sendOutput, SendResponse.class);
            String id = response.data() == null ? null : response.data().id();
            return (id == null || id.isBlank()) ? null : id;
        } catch (RuntimeException e) {
            LOGGER.debug("No message ID in the output of 'wacli send': {}", sendOutput, e);
            return null;
        }
    }

    private record SendResponse(Data data) {
        private record Data(String id) {
        }
    }
}
