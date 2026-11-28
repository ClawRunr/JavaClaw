package ai.javaclaw.usage;

import ai.javaclaw.agent.ChatSessions;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.agent.SessionKind;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Turns a model response into a {@link UsageRecord} and saves it on a thread of its own, so a
 * slow write never holds up the call or the stream that produced it.
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private static final int QUEUE_CAPACITY = 1000;
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final UsageRepository repository;
    private final SessionService sessionService;
    private final Clock clock;
    private final ExecutorService writer;

    @Autowired
    public UsageRecorder(UsageRepository repository, SessionService sessionService) {
        this(repository, sessionService, Clock.systemUTC());
    }

    UsageRecorder(UsageRepository repository, SessionService sessionService, Clock clock) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.clock = clock;
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofPlatform().name("token-usage").daemon().factory());
    }

    /**
     * Records the usage the response reports, later and on the writer thread.
     *
     * @param sessionId the session the call belongs to; null for calls outside a session
     */
    public void record(String sessionId, ChatResponse response) {
        Instant recordedAt = clock.instant();
        try {
            writer.execute(() -> save(sessionId, response, recordedAt));
        } catch (RejectedExecutionException e) {
            log.warn("Dropped a token usage record: the writer is full or stopped");
        }
    }

    private void save(String sessionId, ChatResponse response, Instant recordedAt) {
        try {
            Usage usage = response.getMetadata().getUsage();
            Map<String, Object> metadata = sessionMetadata(sessionId);
            String model = response.getMetadata().getModel();
            repository.save(new UsageRecord(
                    recordedAt,
                    sessionId,
                    attribute(metadata, ChatSessions.CONVERSATION),
                    attribute(metadata, SessionChannel.ATTRIBUTE),
                    attribute(metadata, SessionKind.ATTRIBUTE),
                    model == null || model.isBlank() ? null : model,
                    orZero(usage.getPromptTokens()),
                    orZero(usage.getCompletionTokens()),
                    orZero(usage.getCacheReadInputTokens()),
                    orZero(usage.getCacheWriteInputTokens()),
                    orZero(usage.getTotalTokens())));
        } catch (RuntimeException e) {
            log.warn("Failed to record token usage: {}", e.getMessage());
        }
    }

    private Map<String, Object> sessionMetadata(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        Session session = sessionService.findById(sessionId);
        return session == null || session.metadata() == null ? Map.of() : session.metadata();
    }

    private static String attribute(Map<String, Object> metadata, String name) {
        Object value = metadata.get(name);
        return value == null ? null : value.toString();
    }

    private static int orZero(Number value) {
        return value == null ? 0 : value.intValue();
    }

    /** Saves what is still queued, for a while, before the sessions database closes. */
    @PreDestroy
    void close() throws InterruptedException {
        writer.shutdown();
        if (!writer.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("Token usage writer did not finish in {}s; {} records dropped",
                    SHUTDOWN_WAIT_SECONDS, writer.shutdownNow().size());
        }
    }
}
