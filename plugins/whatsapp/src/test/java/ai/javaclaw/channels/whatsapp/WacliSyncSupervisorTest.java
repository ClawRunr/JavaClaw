package ai.javaclaw.channels.whatsapp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WacliSyncSupervisorTest {

    private WacliProperties properties() {
        WacliProperties properties = new WacliProperties();
        properties.setEnabled(true);
        properties.setWacliPath("wacli");
        properties.setAllowedChatJid("1234567890@s.whatsapp.net");
        return properties;
    }

    private WacliSyncSupervisor newSupervisor(ProcessLauncher launcher, WacliSyncSupervisor.Sleeper sleeper) {
        return newSupervisor(launcher, sleeper);
    }

    private WacliSyncSupervisor newSupervisor(ProcessLauncher launcher, WacliSyncSupervisor.Sleeper sleeper,
                                              WacliSyncSupervisor.Ticker ticker) {
        return new WacliSyncSupervisor(properties(), WacliSyncSupervisor.DEFAULT_WEBHOOK_PORT,
                launcher, sleeper, ticker);
    }

    @Test
    void restartsSyncProcessOnUnexpectedExitAndGivesUpAfterMaxRetries() throws Exception {
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(1);
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(launcher.launch(any())).thenReturn(process);

        WacliSyncSupervisor supervisor = newSupervisor(launcher, millis -> {});

        supervisor.runSyncLoop();

        verify(launcher, times(1 + WacliSyncSupervisor.MAX_RESTART_RETRIES)).launch(any());
        assertThat(supervisor.isRunning()).isFalse();
    }

    @Test
    void resetsRetryCounterAfterHealthyUptimeSoItKeepsRestarting() throws Exception {
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(1);
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(launcher.launch(any())).thenReturn(process);

        AtomicLong clock = new AtomicLong();
        WacliSyncSupervisor.Ticker ticker = () -> clock.getAndAdd(TimeUnit.MINUTES.toNanos(2));

        WacliSyncSupervisor[] ref = new WacliSyncSupervisor[1];
        AtomicInteger backoffs = new AtomicInteger();
        WacliSyncSupervisor.Sleeper sleeper = millis -> {
            if (backoffs.incrementAndGet() >= 20) {
                ref[0].stop();
            }
        };
        WacliSyncSupervisor supervisor = newSupervisor(launcher, sleeper, ticker);
        ref[0] = supervisor;

        supervisor.runSyncLoop();

        verify(launcher, atLeast(1 + WacliSyncSupervisor.MAX_RESTART_RETRIES + 1)).launch(any());
    }

    @Test
    void stopDuringBackoffHaltsTheRestartLoop() throws Exception {
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(1);
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(launcher.launch(any())).thenReturn(process);

        WacliSyncSupervisor[] ref = new WacliSyncSupervisor[1];
        WacliSyncSupervisor supervisor = newSupervisor(launcher, millis -> ref[0].stop());
        ref[0] = supervisor;

        supervisor.runSyncLoop();

        verify(launcher, times(1)).launch(any());
    }

    @Test
    void destroysProcessLaunchedAfterShutdownWasRequested() throws Exception {
        Process process = mock(Process.class);
        WacliSyncSupervisor[] ref = new WacliSyncSupervisor[1];
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        when(launcher.launch(any())).thenAnswer(invocation -> {
            ref[0].stop();
            return process;
        });

        WacliSyncSupervisor supervisor = newSupervisor(launcher, millis -> {});
        ref[0] = supervisor;

        supervisor.runSyncLoop();

        verify(process).destroy();
        verify(process, never()).waitFor();
        verify(launcher, times(1)).launch(any());
    }

    @Test
    void destroysProcessWhenInterruptedWhileAwaitingExit() throws Exception {
        Process process = mock(Process.class);
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor()).thenThrow(new InterruptedException("stopping"));
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        when(launcher.launch(any())).thenReturn(process);

        WacliSyncSupervisor supervisor = newSupervisor(launcher, millis -> {});

        supervisor.runSyncLoop();

        verify(process).destroy();
        verify(launcher, times(1)).launch(any());
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void doesNotStartWhenWacliBinaryIsMissing() throws Exception {
        Process versionProcess = mock(Process.class);
        when(versionProcess.waitFor(anyLong(), any())).thenReturn(true);
        when(versionProcess.exitValue()).thenReturn(1);
        ProcessLauncher launcher = mock(ProcessLauncher.class);
        when(launcher.launch(any())).thenReturn(versionProcess);

        WacliSyncSupervisor supervisor = newSupervisor(launcher, millis -> {});

        boolean started = supervisor.start();

        assertThat(started).isFalse();
        assertThat(supervisor.isRunning()).isFalse();
        verify(launcher, times(1)).launch(any());
    }
}
