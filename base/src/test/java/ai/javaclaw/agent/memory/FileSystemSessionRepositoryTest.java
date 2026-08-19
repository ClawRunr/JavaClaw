package ai.javaclaw.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemSessionRepositoryTest {

    @TempDir
    Path workspace;

    FileSystemSessionRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = new FileSystemSessionRepository(new FileSystemResource(workspace));
    }

    private Session saveSession(String id) {
        return repository.save(Session.builder().id(id).userId("javaclaw").build());
    }

    private SessionEvent userEvent(String sessionId, String eventId, String text) {
        return SessionEvent.builder().id(eventId).sessionId(sessionId).message(new UserMessage(text)).build();
    }

    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    @Test
    void saveAndFindByIdRoundTripsSessionMetadata() {
        repository.save(Session.builder().id("web").userId("javaclaw")
                .metadata(Map.of("channel", "web")).build());

        Session found = repository.findById("web");

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("web");
        assertThat(found.userId()).isEqualTo("javaclaw");
        assertThat(found.metadata()).containsEntry("channel", "web");
    }

    @Test
    void saveCreatesFileAtCorrectPath() {
        saveSession("telegram-42");

        assertThat(workspace.resolve("conversations").resolve("chat-telegram-42.json")).exists();
    }

    @Test
    void findByIdReturnsNullWhenSessionDoesNotExist() {
        assertThat(repository.findById("missing")).isNull();
    }

    @Test
    void savePreservesEventsAndVersionOnMetadataUpdate() {
        saveSession("web");
        repository.appendEvent(userEvent("web", "e1", "Hello"));

        repository.save(Session.builder().id("web").userId("javaclaw").metadata(Map.of("updated", true)).build());

        assertThat(repository.findEvents("web", EventFilter.all())).hasSize(1);
        assertThat(repository.getEventVersion("web")).isEqualTo(1);
    }

    @Test
    void savePreservesCreatedAtOnMetadataUpdate() {
        Instant originalCreatedAt = Instant.parse("2026-01-01T10:00:00Z");
        repository.save(Session.builder().id("web").userId("javaclaw").createdAt(originalCreatedAt).build());

        repository.save(Session.builder().id("web").userId("javaclaw").metadata(Map.of("updated", true)).build());

        assertThat(repository.findById("web").createdAt()).isEqualTo(originalCreatedAt);
    }

    @Test
    void findByUserIdReturnsOnlyMatchingSessions() {
        saveSession("web");
        saveSession("telegram-42");
        repository.save(Session.builder().id("other").userId("someone-else").build());

        List<Session> sessions = repository.findByUserId("javaclaw");

        assertThat(sessions).extracting(Session::id).containsExactlyInAnyOrder("web", "telegram-42");
    }

    @Test
    void findExpiredSessionIdsReturnsOnlyExpiredOnes() {
        repository.save(Session.builder().id("old").userId("javaclaw")
                .expiresAt(Instant.now().minusSeconds(60)).build());
        saveSession("fresh");

        assertThat(repository.findExpiredSessionIds(Instant.now())).containsExactly("old");
    }

    @Test
    void deleteRemovesSession() {
        saveSession("web");

        repository.delete("web");

        assertThat(repository.findById("web")).isNull();
    }

    @Test
    void deleteIsIdempotentWhenSessionDoesNotExist() {
        repository.delete("missing");
    }

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    @Test
    void eventsSurviveARepositoryRestart() throws IOException {
        saveSession("web");
        repository.appendEvent(userEvent("web", "e1", "Hello"));
        repository.appendEvent(SessionEvent.builder().id("e2").sessionId("web")
                .message(new AssistantMessage("Hi there")).build());

        FileSystemSessionRepository reloaded = new FileSystemSessionRepository(new FileSystemResource(workspace));

        List<SessionEvent> events = reloaded.findEvents("web", EventFilter.all());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMessage().getText()).isEqualTo("Hello");
        assertThat(events.get(1).getMessage().getText()).isEqualTo("Hi there");
        assertThat(reloaded.findById("web")).isNotNull();
        assertThat(reloaded.getEventVersion("web")).isEqualTo(2);
    }

    @Test
    void appendEventThrowsWhenSessionDoesNotExist() {
        assertThatThrownBy(() -> repository.appendEvent(userEvent("missing", "e1", "Hello")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appendEventIsIdempotentByEventId() {
        saveSession("web");
        repository.appendEvent(userEvent("web", "e1", "Hello"));
        repository.appendEvent(userEvent("web", "e1", "Hello"));

        assertThat(repository.findEvents("web", EventFilter.all())).hasSize(1);
        assertThat(repository.getEventVersion("web")).isEqualTo(1);
    }

    @Test
    void appendEventPreservesOrderAndIncrementsVersion() {
        saveSession("web");
        repository.appendEvent(userEvent("web", "e1", "first"));
        repository.appendEvent(userEvent("web", "e2", "second"));
        repository.appendEvent(userEvent("web", "e3", "third"));

        assertThat(repository.findEvents("web", EventFilter.all()))
                .extracting(e -> e.getMessage().getText())
                .containsExactly("first", "second", "third");
        assertThat(repository.getEventVersion("web")).isEqualTo(3);
    }

    @Test
    void assistantMessageWithToolCallsRoundTrips() throws IOException {
        saveSession("web");
        repository.appendEvent(SessionEvent.builder().id("e1").sessionId("web")
                .message(AssistantMessage.builder()
                        .content("calling a tool")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "search", "{\"q\":\"x\"}")))
                        .build())
                .build());

        FileSystemSessionRepository reloaded = new FileSystemSessionRepository(new FileSystemResource(workspace));

        SessionEvent event = reloaded.findEvents("web", EventFilter.all()).get(0);
        assertThat(event.hasToolCalls()).isTrue();
        AssistantMessage message = (AssistantMessage) event.getMessage();
        assertThat(message.getToolCalls()).hasSize(1);
        assertThat(message.getToolCalls().get(0).name()).isEqualTo("search");
        assertThat(message.getToolCalls().get(0).arguments()).isEqualTo("{\"q\":\"x\"}");
    }

    @Test
    void toolResponseMessageRoundTrips() throws IOException {
        saveSession("web");
        repository.appendEvent(SessionEvent.builder().id("e1").sessionId("web")
                .message(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "search", "result text")))
                        .build())
                .build());

        FileSystemSessionRepository reloaded = new FileSystemSessionRepository(new FileSystemResource(workspace));

        ToolResponseMessage message = (ToolResponseMessage) reloaded.findEvents("web", EventFilter.all())
                .get(0).getMessage();
        assertThat(message.getResponses()).hasSize(1);
        assertThat(message.getResponses().get(0).responseData()).isEqualTo("result text");
    }

    @Test
    void findEventsReturnsEmptyListWhenSessionDoesNotExist() {
        assertThat(repository.findEvents("missing", EventFilter.all())).isEmpty();
    }

    @Test
    void findEventsAppliesLastNFilter() {
        saveSession("web");
        repository.appendEvent(userEvent("web", "e1", "first"));
        repository.appendEvent(userEvent("web", "e2", "second"));
        repository.appendEvent(userEvent("web", "e3", "third"));

        assertThat(repository.findEvents("web", EventFilter.lastN(2)))
                .extracting(e -> e.getMessage().getText())
                .containsExactly("second", "third");
    }

    @Test
    void getEventVersionReturnsZeroWhenSessionDoesNotExist() {
        assertThat(repository.getEventVersion("missing")).isZero();
    }

    // -----------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------

    @Test
    void compactEventsArchivesOldEventsAndKeepsRetainedOnes() {
        saveSession("web");
        SessionEvent oldest = userEvent("web", "e1", "oldest");
        SessionEvent recent = userEvent("web", "e2", "recent");
        repository.appendEvent(oldest);
        repository.appendEvent(recent);

        boolean swapped = repository.compactEvents("web", List.of(oldest), List.of(recent),
                repository.getEventVersion("web"));

        assertThat(swapped).isTrue();
        assertThat(repository.findEvents("web", EventFilter.active()))
                .extracting(e -> e.getMessage().getText())
                .containsExactly("recent");
        assertThat(repository.findEvents("web", EventFilter.all())).hasSize(2);
    }

    @Test
    void compactEventsReturnsFalseOnVersionMismatch() {
        saveSession("web");
        SessionEvent event = userEvent("web", "e1", "Hello");
        repository.appendEvent(event);

        boolean swapped = repository.compactEvents("web", List.of(event), List.of(), 999);

        assertThat(swapped).isFalse();
        assertThat(repository.findEvents("web", EventFilter.active())).hasSize(1);
    }

    @Test
    void compactEventsThrowsWhenSessionDoesNotExist() {
        assertThatThrownBy(() -> repository.compactEvents("missing", List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
