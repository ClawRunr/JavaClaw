package ai.javaclaw.channels.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Supervises the long-running {@code wacli sync} subprocess. It verifies the binary is present,
 * launches the process on a background daemon thread, restarts it with exponential backoff when it
 * exits unexpectedly, and gives up after too many failures in quick succession.
 *
 * <p>All lifecycle methods are safe to call from different threads; cross-thread state is held in
 * {@code volatile} fields. Time and process creation are injected as seams so the restart/backoff
 * logic can be driven deterministically in tests.
 */
class WacliSyncSupervisor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WacliSyncSupervisor.class);

    static final int MAX_RESTART_RETRIES = 5;

    static final long HEALTHY_UPTIME_MILLIS = 60_000L;

    static final int DEFAULT_WEBHOOK_PORT = 8080;

    private static final long BASE_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    private static final long VERSION_CHECK_TIMEOUT_SECONDS = 10L;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Ticker {
        long nanoTime();
    }

    private final WacliProperties properties;
    private final int webhookPort;
    private final ProcessLauncher processLauncher;
    private final Sleeper sleeper;
    private final Ticker ticker;

    private volatile boolean running;
    private volatile boolean shuttingDown;
    private volatile Process syncProcess;
    private volatile String lastStderrLine;
    private volatile Thread monitorThread;

    WacliSyncSupervisor(WacliProperties properties, int webhookPort,
                        ProcessLauncher processLauncher, Sleeper sleeper, Ticker ticker) {
        this.properties = properties;
        this.webhookPort = webhookPort;
        this.processLauncher = processLauncher;
        this.sleeper = sleeper;
        this.ticker = ticker;
    }

    /**
     * Verifies the wacli binary and, if it is runnable, starts the background monitor thread.
     *
     * @return {@code true} if supervision started, {@code false} if wacli is unavailable
     */
    boolean start() {
        if (!verifyWacliBinary()) {
            LOGGER.error("wacli binary '{}' not found or not runnable. The WhatsApp channel is disabled. "
                            + "Install wacli (macOS: 'brew install openclaw/tap/wacli', "
                            + "Linux: 'go install github.com/openclaw/wacli@latest').",
                    properties.getWacliPath());
            return false;
        }
        running = true;
        monitorThread = new Thread(this::runSyncLoop, "wacli-sync-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
        return true;
    }

    void stop() {
        shuttingDown = true;
        running = false;
        Process process = syncProcess;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        Thread monitor = monitorThread;
        if (monitor != null) {
            monitor.interrupt();
        }
    }

    boolean isRunning() {
        return running;
    }

    void runSyncLoop() {
        int retries = 0;
        while (!shuttingDown) {
            long startedNanos = ticker.nanoTime();
            launchSyncProcessAndAwaitExit();
            if (shuttingDown) {
                return;
            }
            long upMillis = TimeUnit.NANOSECONDS.toMillis(ticker.nanoTime() - startedNanos);
            if (upMillis >= HEALTHY_UPTIME_MILLIS) {
                retries = 0;
            }
            retries++;
            if (retries > MAX_RESTART_RETRIES) {
                LOGGER.error("wacli sync process failed {} times in quick succession; "
                                + "giving up and stopping the WhatsApp channel.{}",
                        retries, reasonSuffix());
                running = false;
                return;
            }
            backoff(retries);
        }
    }

    private void launchSyncProcessAndAwaitExit() {
        Process process;
        try {
            process = processLauncher.launch(syncProcessBuilder());
        } catch (IOException e) {
            LOGGER.warn("Failed to start wacli sync process", e);
            return;
        }
        this.syncProcess = process;
        this.lastStderrLine = null;
        if (shuttingDown) {
            process.destroy();
            return;
        }
        Thread pump = pumpStderrToDebugLog(process);
        try {
            int exitCode = process.waitFor();
            pump.join(TimeUnit.SECONDS.toMillis(1));
            if (!shuttingDown) {
                LOGGER.warn("wacli sync process exited unexpectedly with code {}{}", exitCode, reasonSuffix());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shuttingDown = true;
            process.destroy();
        }
    }

    private String reasonSuffix() {
        String reason = lastStderrLine;
        return (reason == null || reason.isBlank()) ? "" : " (" + reason + ")";
    }

    private ProcessBuilder syncProcessBuilder() {
        List<String> command = List.of(
                properties.getWacliPath(), "sync", "--follow",
                "--webhook", webhookUrl(),
                "--webhook-allow-private");
        return new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD);
    }

    private String webhookUrl() {
        return "http://localhost:" + webhookPort + "/api/whatsapp/webhook";
    }

    private boolean verifyWacliBinary() {
        try {
            Process process = processLauncher.launch(
                    new ProcessBuilder(List.of(properties.getWacliPath(), "version"))
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            if (!process.waitFor(VERSION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Thread pumpStderrToDebugLog(Process process) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.debug("[wacli] {}", line);
                    if (!line.isBlank()) {
                        lastStderrLine = line;
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Stopped reading wacli stderr", e);
            }
        }, "wacli-stderr-pump");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private void backoff(int attempt) {
        long delay = Math.min(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)), MAX_BACKOFF_MILLIS);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shuttingDown = true;
        }
    }
}
