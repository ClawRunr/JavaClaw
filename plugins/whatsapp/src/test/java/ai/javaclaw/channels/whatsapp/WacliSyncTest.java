package ai.javaclaw.channels.whatsapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WacliSyncTest {

    /** Short enough that the waits between restarts do not slow the tests down. */
    private static final Duration RESTART_DELAY = Duration.ofMillis(1);

    /** Longer than any test run, so every crash counts as another one in the same burst. */
    private static final Duration NEVER_HEALTHY = Duration.ofHours(1);

    /** Zero, so every run counts as having stayed up long enough. */
    private static final Duration ALWAYS_HEALTHY = Duration.ZERO;

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /** How often the sync asked for a new subprocess. */
    private final AtomicInteger starts = new AtomicInteger();

    private WacliSync sync;

    @AfterEach
    void tearDown() {
        if (sync != null) {
            sync.stop();
        }
    }

    /** A subprocess that has already exited with an error. */
    private static Process crashedProcess() throws InterruptedException {
        Process process = mock(Process.class);
        when(process.waitFor()).thenReturn(1);
        return process;
    }

    private WacliSync syncOf(Process process, Duration healthyUptime) {
        return new WacliSync(() -> {
            starts.incrementAndGet();
            return process;
        }, RESTART_DELAY, healthyUptime);
    }

    /** Waits for something the background thread is expected to do, rather than sleeping blindly. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void restartsTheSubprocessWheneverItDies() throws Exception {
        sync = syncOf(crashedProcess(), ALWAYS_HEALTHY);

        sync.start();

        // Every run looks healthy, so the failure count keeps resetting and it never gives up.
        int wellPastTheLimit = 3 * WacliSync.MAX_RESTART_RETRIES;
        await("%d restarts".formatted(wellPastTheLimit), () -> starts.get() >= wellPastTheLimit);
        assertThat(sync.isRunning()).isTrue();
    }

    @Test
    void givesUpAfterTooManyFailuresInQuickSuccession() throws Exception {
        sync = syncOf(crashedProcess(), NEVER_HEALTHY);

        sync.start();

        await("the sync to give up", () -> !sync.isRunning());
        assertThat(starts).hasValue(1 + WacliSync.MAX_RESTART_RETRIES);
    }

    @Test
    void isNotRunningUntilStarted() throws Exception {
        sync = syncOf(crashedProcess(), ALWAYS_HEALTHY);

        assertThat(sync.isRunning()).isFalse();
        assertThat(starts).hasValue(0);
    }

    @Test
    void stopKillsTheSubprocessAndEndsTheSchedule() throws Exception {
        // A subprocess that runs until it is destroyed, like the real 'wacli sync'.
        CountDownLatch destroyed = new CountDownLatch(1);
        Process process = mock(Process.class);
        when(process.waitFor()).thenAnswer(invocation -> {
            destroyed.await();
            return 143;
        });
        doAnswer(invocation -> {
            destroyed.countDown();
            return null;
        }).when(process).destroy();

        sync = syncOf(process, ALWAYS_HEALTHY);
        sync.start();
        await("the subprocess to start", () -> starts.get() == 1);

        sync.stop();

        verify(process).destroy();
        assertThat(sync.isRunning()).isFalse();
        assertThat(starts).hasValue(1);
    }

    @Test
    void countsAFailureWhenTheSubprocessCannotBeStartedAtAll() {
        sync = new WacliSync(() -> {
            starts.incrementAndGet();
            throw new IOException("wacli is not there");
        }, RESTART_DELAY, NEVER_HEALTHY);

        sync.start();

        // A launch that fails outright must count against us, not read as a clean run.
        await("the sync to give up", () -> !sync.isRunning());
        assertThat(starts).hasValue(1 + WacliSync.MAX_RESTART_RETRIES);
    }
}
