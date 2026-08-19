package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WacliWhatsAppChannelTest {

    private final ChannelRegistry channelRegistry = new ChannelRegistry();
    private final CountDownLatch syncRelease = new CountDownLatch(1);
    private final AtomicReference<List<String>> sendCommand = new AtomicReference<>();
    private final Process sendProcess = mock(Process.class);
    private WacliWhatsAppChannel started;

    @AfterEach
    void tearDown() {
        if (started != null) {
            started.stop();
        }
        syncRelease.countDown();
    }

    private WacliProperties properties() {
        WacliProperties properties = new WacliProperties();
        properties.setEnabled(true);
        properties.setWacliPath("wacli");
        properties.setAllowedChatJid("1234567890@s.whatsapp.net");
        return properties;
    }

    private WacliWhatsAppChannel newChannel(WacliProperties props, ProcessLauncher launcher,
                                            WacliSyncSupervisor.Sleeper sleeper) {
        return new WacliWhatsAppChannel(props, channelRegistry,
                WacliSyncSupervisor.DEFAULT_WEBHOOK_PORT, launcher, sleeper, System::nanoTime);
    }

    private WacliWhatsAppChannel startedChannel(WacliProperties props) throws InterruptedException {
        Process versionProcess = mock(Process.class);
        when(versionProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(versionProcess.exitValue()).thenReturn(0);

        Process syncProcess = mock(Process.class);
        when(syncProcess.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(syncProcess.waitFor()).thenAnswer(invocation -> {
            syncRelease.await();
            return 0;
        });

        ProcessLauncher launcher = processBuilder -> {
            List<String> command = processBuilder.command();
            if (command.contains("version")) {
                return versionProcess;
            }
            if (command.contains("sync")) {
                return syncProcess;
            }
            sendCommand.set(command);
            return sendProcess;
        };

        WacliWhatsAppChannel channel = newChannel(props, launcher, _ -> { });
        channel.start();
        started = channel;
        return channel;
    }

    @Test
    void sendMessageInvokesWacliSendWithConfiguredArguments() throws Exception {
        when(sendProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(sendProcess.exitValue()).thenReturn(0);
        when(sendProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

        WacliWhatsAppChannel channel = startedChannel(properties());

        channel.sendMessage("hi there");

        assertThat(sendCommand.get()).containsExactly(
                "wacli", "send", "text",
                "--to", "1234567890@s.whatsapp.net",
                "--message", "hi there",
                "--json");
    }

    @Test
    void remembersSentMessageIdSoItsEchoCanBeSuppressed() throws Exception {
        // Exact 'wacli send --json' output: the message ID is nested under "data".
        String wacliSendResponse = """
                {
                  "success": true,
                  "data": { "id": "3EB062AF5A2B5A7066B238", "sent": true, "to": "95318997741682@lid" },
                  "error": null
                }
                """;
        when(sendProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(sendProcess.exitValue()).thenReturn(0);
        when(sendProcess.getInputStream()).thenReturn(new ByteArrayInputStream(
                wacliSendResponse.getBytes(StandardCharsets.UTF_8)));

        WacliWhatsAppChannel channel = startedChannel(properties());

        channel.sendMessage("hi there");

        assertThat(channel.wasSentByAgent("3EB062AF5A2B5A7066B238")).isTrue();
        assertThat(channel.wasSentByAgent("someone-elses-id")).isFalse();
        assertThat(channel.wasSentByAgent(null)).isFalse();
    }

    @Test
    void sendMessageForciblyDestroysProcessThatTimesOut() throws Exception {
        when(sendProcess.waitFor(anyLong(), any())).thenReturn(false);

        WacliWhatsAppChannel channel = startedChannel(properties());

        channel.sendMessage("hello");

        verify(sendProcess).destroyForcibly();
        verify(sendProcess, never()).exitValue();
    }

    @Test
    void skipsLaunchWhenNotRunning() throws IOException {
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        WacliWhatsAppChannel channel = newChannel(properties(), launcher, millis -> { });

        channel.stop();

        channel.sendMessage("hello");

        verify(launcher, times(0)).launch(any());
    }

    @Test
    void doesNotRegisterWhenWacliBinaryIsMissing() throws Exception {
        Process versionProcess = mock(Process.class);
        when(versionProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(versionProcess.exitValue()).thenReturn(1);
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        when(launcher.launch(any())).thenReturn(versionProcess);

        WacliWhatsAppChannel channel = newChannel(properties(), launcher, millis -> { });

        channel.start();

        assertThat(channel.isRunning()).isFalse();
        assertThat(channelRegistry.getLatestChannel()).isNull();
    }

    @Test
    void stopUnregistersChannelFromRegistry() throws InterruptedException {
        WacliWhatsAppChannel channel = startedChannel(properties());
        assertThat(channelRegistry.getLatestChannel()).isSameAs(channel);

        channel.stop();
        started = null;

        assertThat(channelRegistry.getLatestChannel()).isNull();
    }
}
