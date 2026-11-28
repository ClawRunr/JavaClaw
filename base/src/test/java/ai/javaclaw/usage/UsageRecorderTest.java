package ai.javaclaw.usage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageRecorderTest {

    static final Instant NOW = Instant.parse("2026-11-24T15:30:12Z");
    static final String SESSION = "20261124_153012_a1b2c3d4";

    @Mock UsageRepository repository;
    @Mock SessionService sessionService;

    UsageRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new UsageRecorder(repository, sessionService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        recorder.close();
    }

    @Test
    void takesConversationChannelAndKindFromTheSessionMetadata() {
        when(sessionService.findById(SESSION)).thenReturn(session(Map.of(
                "conversation", "telegram:42", "channel", "telegram", "kind", "chat")));

        recorder.record(SESSION, response("claude-sonnet-4-6", new DefaultUsage(1200, 350, 1550, null, 800L, 100L)));

        assertThat(saved()).isEqualTo(new UsageRecord(NOW, SESSION, "telegram:42", "telegram", "chat",
                "claude-sonnet-4-6", 1200, 350, 800, 100, 1550));
    }

    @Test
    void callsWithoutSessionLeaveTheSessionColumnsEmpty() {
        recorder.record(null, response("gpt-5.4", new DefaultUsage(10, 5)));

        UsageRecord record = saved();
        assertThat(record.sessionId()).isNull();
        assertThat(record.conversation()).isNull();
        assertThat(record.channel()).isNull();
        assertThat(record.kind()).isNull();
        assertThat(record.model()).isEqualTo("gpt-5.4");
        verifyNoInteractions(sessionService);
    }

    @Test
    void anUnknownSessionLeavesTheSessionColumnsEmpty() {
        recorder.record(SESSION, response("m", new DefaultUsage(10, 5)));

        UsageRecord record = saved();
        assertThat(record.sessionId()).isEqualTo(SESSION);
        assertThat(record.conversation()).isNull();
    }

    @Test
    void missingCacheTokensAreZeroAndABlankModelIsNull() {
        recorder.record(null, response("", new DefaultUsage(10, 5)));

        UsageRecord record = saved();
        assertThat(record.cacheReadTokens()).isZero();
        assertThat(record.cacheWriteTokens()).isZero();
        assertThat(record.totalTokens()).isEqualTo(15);
        assertThat(record.model()).isNull();
    }

    @Test
    void savesOnAnotherThread() {
        AtomicReference<Thread> savedOn = new AtomicReference<>();
        doAnswer(_ -> {
            savedOn.set(Thread.currentThread());
            return null;
        }).when(repository).save(any());

        recorder.record(null, response("m", new DefaultUsage(1, 1)));

        verify(repository, timeout(2000)).save(any());
        assertThat(savedOn.get()).isNotSameAs(Thread.currentThread());
        assertThat(savedOn.get().getName()).isEqualTo("token-usage");
    }

    @Test
    void aFailedSaveDoesNotStopLaterOnes() {
        doThrow(new RuntimeException("disk full")).doNothing().when(repository).save(any());

        recorder.record(null, response("m", new DefaultUsage(1, 1)));
        recorder.record(null, response("m", new DefaultUsage(2, 2)));

        verify(repository, timeout(2000).times(2)).save(any());
    }

    @Test
    void closeSavesWhatIsStillQueued() throws InterruptedException {
        recorder.record(null, response("m", new DefaultUsage(1, 1)));
        recorder.record(null, response("m", new DefaultUsage(2, 2)));

        recorder.close();

        verify(repository, times(2)).save(any());
    }

    private UsageRecord saved() {
        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(repository, timeout(2000)).save(captor.capture());
        return captor.getValue();
    }

    private static Session session(Map<String, Object> metadata) {
        return Session.builder().id(SESSION).createdAt(NOW).metadata(metadata).build();
    }

    private static ChatResponse response(String model, Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().model(model).usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))), metadata);
    }
}
