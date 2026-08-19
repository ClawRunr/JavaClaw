package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class WacliWhatsAppChannel implements Channel {

    private static final Logger LOGGER = LoggerFactory.getLogger(WacliWhatsAppChannel.class);

    static final String CHANNEL_ID = "whatsapp";

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** How many recently-sent message IDs to remember for echo suppression. */
    private static final int RECENT_SENT_ID_CAPACITY = 256;

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final WacliProperties properties;
    private final ChannelRegistry channelRegistry;
    private final ProcessLauncher processLauncher;
    private final WacliSyncSupervisor supervisor;

    /**
     * IDs of messages this channel has sent, so inbound webhooks that echo our own replies can be
     * distinguished from messages a human actually typed (which matters on a single WhatsApp
     * account, where both carry {@code FromMe=true}). Bounded, insertion-order eviction; the echo
     * always arrives within seconds of the send.
     */
    private final Set<String> recentlySentIds = Collections.newSetFromMap(
            // +1 capacity leaves room for the transient entry that briefly overshoots before
            // removeEldestEntry (checked after insertion) evicts it, avoiding a resize.
            Collections.synchronizedMap(new LinkedHashMap<>(RECENT_SENT_ID_CAPACITY + 1, 1.0f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > RECENT_SENT_ID_CAPACITY;
                }
            }));

     public WacliWhatsAppChannel(WacliProperties properties, ChannelRegistry channelRegistry, int webhookPort) {
        this(properties, channelRegistry, webhookPort, ProcessBuilder::start, Thread::sleep, System::nanoTime);
    }

    WacliWhatsAppChannel(WacliProperties properties, ChannelRegistry channelRegistry, int webhookPort,
                         ProcessLauncher processLauncher,
                         WacliSyncSupervisor.Sleeper sleeper, WacliSyncSupervisor.Ticker ticker) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.processLauncher = processLauncher;
        this.supervisor = new WacliSyncSupervisor(properties, webhookPort, processLauncher, sleeper, ticker);
    }

    @Override
    public String getName() {
        return CHANNEL_ID;
    }

    @PostConstruct
    public void start() {
        if (supervisor.start()) {
            channelRegistry.registerChannel(this);
            LOGGER.info("Started WhatsApp integration via wacli");
        }
    }

    @PreDestroy
    public void stop() {
        supervisor.stop();
        channelRegistry.unregisterChannel(this);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!supervisor.isRunning()) {
            LOGGER.warn("WhatsApp channel is not running, cannot send message '{}'", message);
            return;
        }

        List<String> command = List.of(
                properties.getWacliPath(), "send", "text",
                "--to", properties.getAllowedChatJid(),
                "--message", message,
                "--json");

        Process process = null;
        try {
            process = processLauncher.launch(new ProcessBuilder(command).redirectErrorStream(true));
            if (!process.waitFor(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.error("wacli send timed out after {}s for WhatsApp message '{}'",
                        SEND_TIMEOUT_SECONDS, message);
                return;
            }
            String output = readOutput(process);
            if (process.exitValue() != 0) {
                LOGGER.error("wacli send exited with code {}. Output (stdout/stderr): {}",
                        process.exitValue(), output);
            } else {
                rememberSentMessageId(output);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to run wacli send for WhatsApp message '{}'", message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            LOGGER.error("Interrupted while sending WhatsApp message '{}'", message, e);
        }
    }

    private static String readOutput(Process process) throws IOException {
        try (InputStream in = process.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * @return {@code true} if {@code messageId} identifies a message this channel recently sent, so
     *         an inbound webhook echoing our own reply can be dropped instead of answered again
     */
    boolean wasSentByAgent(String messageId) {
        return messageId != null && recentlySentIds.contains(messageId);
    }

    private void rememberSentMessageId(String wacliSendOutput) {
        String id = extractMessageId(wacliSendOutput);
        if (id != null) {
            recentlySentIds.add(id);
        } else {
            LOGGER.debug("No message ID in wacli send output; this reply's echo may not be suppressed: {}",
                    wacliSendOutput);
        }
    }

    private static String extractMessageId(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            WacliSendResponse response = JSON_MAPPER.readValue(json, WacliSendResponse.class);
            String id = response.data() == null ? null : response.data().id();
            return (id == null || id.isBlank()) ? null : id;
        } catch (RuntimeException e) {
            LOGGER.debug("Could not parse wacli send output: {}", json, e);
            return null;
        }
    }

    private record WacliSendResponse(Data data) {
        private record Data(String id) {
        }
    }

    boolean isRunning() {
        return supervisor.isRunning();
    }
}
