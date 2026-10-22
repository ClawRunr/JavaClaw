package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.utils.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the long-running {@code wacli sync} subprocess alive. One scheduled thread runs it, waits
 * a moment once it dies, and runs it again -- giving up only after {@link #MAX_RESTART_RETRIES}
 * failures in quick succession.
 *
 * <p>"In quick succession" is what {@code healthyUptime} decides: a run that stayed up that long
 * counts as a fresh problem rather than another one in the same burst. Without it the retry limit
 * would be a lifetime count, and a subprocess that dies once a week -- which is normal, WhatsApp
 * Web sessions drop -- would switch the channel off for good after five weeks.
 *
 * <p>Starting the process and both durations are passed in, so this class is only about staying
 * alive and tests can drive the restarts without really waiting.
 *
 * <p>Several threads meet here: Spring's startup and shutdown call {@link #start} and
 * {@link #stop}, the subprocess is watched on the "wacli-sync" thread, and any thread sending a
 * message asks {@link #isRunning} first -- hence the {@code volatile} fields.
 */
class WacliSync {

    static final int MAX_RESTART_RETRIES = 5;

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WacliSync.class);

    /** How long to wait before starting the subprocess again after it died. */
    private static final Duration RESTART_DELAY = Duration.ofSeconds(5);

    /** How long the subprocess must stay up before its next crash counts as a fresh problem. */
    private static final Duration HEALTHY_UPTIME = Duration.ofSeconds(60);

    private static final int STOP_GRACE_SECONDS = 5;

    private final ProcessStarter processStarter;
    private final Duration restartDelay;
    private final Duration healthyUptime;

    /** Non-null exactly while we are syncing: set by {@link #start}, cleared when we stop. */
    private volatile ScheduledExecutorService executor;

    /** The subprocess being watched, so {@link #stop} can kill it from its own thread. */
    private volatile Process syncProcess;

    /** Failures in quick succession. Only ever touched on the "wacli-sync" thread. */
    private int failures;

    WacliSync(ProcessStarter processStarter) {
        this(processStarter, RESTART_DELAY, HEALTHY_UPTIME);
    }

    WacliSync(ProcessStarter processStarter, Duration restartDelay, Duration healthyUptime) {
        this.processStarter = processStarter;
        this.restartDelay = restartDelay;
        this.healthyUptime = healthyUptime;
    }

    /** Starts syncing. Does nothing if we are already syncing. */
    void start() {
        if (isRunning()) {
            return;
        }
        // Reset before scheduling: the first run starts immediately on the sync thread, and a reset
        // after it could wipe failures that run has already counted.
        failures = 0;
        executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("wacli-sync", true));
        executor.scheduleWithFixedDelay(this::syncOnce, 0, restartDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    void stop() {
        ScheduledExecutorService syncing = executor;
        executor = null;
        if (syncing == null) {
            return;
        }
        syncing.shutdown();

        Process process = syncProcess;
        if (process != null) {
            process.destroy();
        }
        try {
            if (!syncing.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                if (process != null) {
                    process.destroyForcibly();
                }
                syncing.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            syncing.shutdownNow();
        }
    }

    boolean isRunning() {
        return executor != null;
    }

    /** One turn of the schedule: run the subprocess until it exits, then decide whether to carry on. */
    private void syncOnce() {
        long startedAt = System.nanoTime();
        runSyncProcess();
        if (!isRunning()) {
            return;
        }

        Duration uptime = Duration.ofNanos(System.nanoTime() - startedAt);
        failures = uptime.compareTo(healthyUptime) >= 0 ? 1 : failures + 1;
        if (failures > MAX_RESTART_RETRIES) {
            LOGGER.error("'wacli sync' failed {} times in quick succession, "
                    + "so the WhatsApp channel is stopping.", failures);
            giveUp();
        }
    }

    /**
     * Stops restarting. Called from the "wacli-sync" thread, so unlike {@link #stop} it must not
     * wait for that thread to finish -- it would be waiting for itself.
     */
    private void giveUp() {
        ScheduledExecutorService syncing = executor;
        executor = null;
        if (syncing != null) {
            syncing.shutdown();
        }
    }

    /** Starts {@code wacli sync} and waits here until it exits. */
    private void runSyncProcess() {
        Process process;
        try {
            process = processStarter.start();
        } catch (IOException e) {
            LOGGER.warn("Failed to start 'wacli sync'", e);
            return;
        }
        syncProcess = process;
        if (!isRunning()) {
            process.destroy();
            return;
        }

        try {
            int exitCode = process.waitFor();
            if (isRunning()) {
                LOGGER.warn("'wacli sync' exited unexpectedly with code {}", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }
}
