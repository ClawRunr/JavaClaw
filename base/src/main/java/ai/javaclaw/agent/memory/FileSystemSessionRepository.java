package ai.javaclaw.agent.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File-backed {@link SessionRepository} that persists each session — metadata and
 * event log — as a JSON file inside the agent workspace, so chat history survives
 * restarts.
 *
 * <p>The session ID is the channel name, e.g. {@code web} or
 * {@code telegram-123456789}. The repository maps this to a flat file:
 * {@code {workspace}/conversations/chat-{sessionId}.json}
 *
 * <p>Semantics mirror {@link org.springframework.ai.session.InMemorySessionRepository}:
 * appends are idempotent by event id, compaction uses a compare-and-swap on the
 * event-log version, and reads of unknown sessions return empty rather than throwing.
 * Messages are stored the same way as the library's JDBC repository: message type,
 * plain text, plus a JSON blob for tool calls / tool responses. All operations are
 * serialized on a single lock — sufficient for a single-node agent.
 */
@Component
public class FileSystemSessionRepository implements SessionRepository {

    private final Path conversationsDir;
    private final JsonMapper jsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Object lock = new Object();

    public FileSystemSessionRepository(@Value("${agent.workspace:Unknown}") Resource workspaceDir) throws IOException {
        this.conversationsDir = workspaceDir.getFilePath().resolve("conversations");
    }

    @Override
    public Session save(Session session) {
        synchronized (lock) {
            SessionFile existing = load(session.id());
            List<EventEntry> events = existing != null ? existing.events() : List.of();
            long version = existing != null ? existing.eventVersion() : 0L;
            // createdAt is immutable: keep the original timestamp on updates
            String createdAt = existing != null ? existing.createdAt() : session.createdAt().toString();
            write(new SessionFile(session.id(), session.userId(),
                    createdAt, session.expiresAt() != null ? session.expiresAt().toString() : null,
                    session.metadata(), version, events));
            return session;
        }
    }

    @Override
    public Session findById(String sessionId) {
        synchronized (lock) {
            SessionFile data = load(sessionId);
            return data != null ? toSession(data) : null;
        }
    }

    @Override
    public List<Session> findByUserId(String userId) {
        synchronized (lock) {
            return loadAll().stream()
                    .filter(data -> userId.equals(data.userId()))
                    .map(FileSystemSessionRepository::toSession)
                    .toList();
        }
    }

    @Override
    public List<String> findExpiredSessionIds(Instant before) {
        synchronized (lock) {
            return loadAll().stream()
                    .filter(data -> data.expiresAt() != null && Instant.parse(data.expiresAt()).isBefore(before))
                    .map(SessionFile::id)
                    .toList();
        }
    }

    @Override
    public void delete(String sessionId) {
        synchronized (lock) {
            try {
                Files.deleteIfExists(resolveFile(sessionId));
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete session: " + sessionId, e);
            }
        }
    }

    @Override
    public void appendEvent(SessionEvent event) {
        synchronized (lock) {
            SessionFile data = load(event.getSessionId());
            if (data == null) {
                throw new IllegalArgumentException("Session not found: " + event.getSessionId());
            }
            boolean alreadyAppended = data.events().stream().anyMatch(e -> e.id().equals(event.getId()));
            if (alreadyAppended) {
                // Idempotent replay of an already-committed event: no duplicate, no version bump
                return;
            }
            List<EventEntry> events = new ArrayList<>(data.events());
            events.add(toEntry(event));
            write(data.withEvents(events));
        }
    }

    @Override
    public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
                                 List<SessionEvent> retainedEvents, long expectedVersion) {
        synchronized (lock) {
            SessionFile data = load(sessionId);
            if (data == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            if (data.eventVersion() != expectedVersion) {
                return false;
            }
            // Previously-archived events first, then the newly-archived ones, then the new
            // active window. Any other previously-active event (e.g. a superseded synthetic
            // summary) is dropped.
            List<EventEntry> events = new ArrayList<>();
            data.events().stream().filter(EventEntry::archived).forEach(events::add);
            archivedEvents.forEach(e -> events.add(toEntry(e.asArchived())));
            retainedEvents.forEach(e -> events.add(toEntry(e)));
            write(data.withEvents(events));
            return true;
        }
    }

    @Override
    public long getEventVersion(String sessionId) {
        synchronized (lock) {
            SessionFile data = load(sessionId);
            return data != null ? data.eventVersion() : 0L;
        }
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
        synchronized (lock) {
            SessionFile data = load(sessionId);
            if (data == null) {
                return List.of();
            }
            List<SessionEvent> matched = data.events().stream()
                    .map(entry -> toEvent(sessionId, entry))
                    .filter(filter::matches)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            if (filter.lastN() != null && matched.size() > filter.lastN()) {
                matched = matched.subList(matched.size() - filter.lastN(), matched.size());
            }
            if (filter.pageSize() != null) {
                int page = filter.page() != null ? filter.page() : 0;
                int fromIdx = page * filter.pageSize();
                matched = fromIdx >= matched.size() ? new ArrayList<>()
                        : matched.subList(fromIdx, Math.min(fromIdx + filter.pageSize(), matched.size()));
            }
            return List.copyOf(matched);
        }
    }

    // -------------------------------------------------------------------------
    // File access
    // -------------------------------------------------------------------------

    private SessionFile load(String sessionId) {
        Path file = resolveFile(sessionId);
        if (!Files.exists(file)) return null;
        try {
            return jsonMapper.readValue(Files.readString(file), SessionFile.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read session: " + sessionId, e);
        }
    }

    private List<SessionFile> loadAll() {
        if (!Files.exists(conversationsDir)) return List.of();
        try (Stream<Path> files = Files.list(conversationsDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("chat-") && name.endsWith(".json"))
                    .map(name -> load(name.substring("chat-".length(), name.length() - ".json".length())))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    private void write(SessionFile data) {
        Path file = resolveFile(data.id());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, jsonMapper.writeValueAsString(data),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session: " + data.id(), e);
        }
    }

    private Path resolveFile(String sessionId) {
        return conversationsDir.resolve("chat-" + sessionId + ".json");
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private static Session toSession(SessionFile data) {
        Session.Builder builder = Session.builder()
                .id(data.id())
                .userId(data.userId())
                .createdAt(Instant.parse(data.createdAt()))
                .metadata(data.metadata() != null ? data.metadata() : Map.of());
        if (data.expiresAt() != null) {
            builder.expiresAt(Instant.parse(data.expiresAt()));
        }
        return builder.build();
    }

    private EventEntry toEntry(SessionEvent event) {
        Message msg = event.getMessage();
        return new EventEntry(event.getId(), event.getTimestamp().toString(), msg.getMessageType().name(),
                msg.getText(), messageDataToJson(msg), event.isArchived(), event.getBranch(), event.getMetadata());
    }

    private SessionEvent toEvent(String sessionId, EventEntry entry) {
        return SessionEvent.builder()
                .id(entry.id())
                .sessionId(sessionId)
                .timestamp(Instant.parse(entry.timestamp()))
                .message(toMessage(MessageType.valueOf(entry.messageType()), entry.text(), entry.messageData()))
                .branch(entry.branch())
                .archived(entry.archived())
                .metadata(entry.metadata() != null ? entry.metadata() : Map.of())
                .build();
    }

    /**
     * Type-specific message payload as JSON — tool calls for assistant messages,
     * tool responses for tool messages, {@code null} otherwise (matches the
     * serialization used by the library's JDBC repository).
     */
    private String messageDataToJson(Message message) {
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            return jsonMapper.writeValueAsString(am.getToolCalls());
        }
        if (message instanceof ToolResponseMessage trm) {
            return jsonMapper.writeValueAsString(trm.getResponses());
        }
        return null;
    }

    private Message toMessage(MessageType type, String text, String messageData) {
        return switch (type) {
            case USER -> new UserMessage(text != null ? text : "");
            case SYSTEM -> new SystemMessage(text != null ? text : "");
            case ASSISTANT -> {
                if (messageData != null && !messageData.isBlank()) {
                    List<AssistantMessage.ToolCall> toolCalls = jsonMapper.readValue(messageData,
                            new TypeReference<List<AssistantMessage.ToolCall>>() { });
                    yield AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
                }
                yield new AssistantMessage(text != null ? text : "");
            }
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = messageData != null && !messageData.isBlank()
                        ? jsonMapper.readValue(messageData, new TypeReference<List<ToolResponseMessage.ToolResponse>>() { })
                        : List.of();
                yield ToolResponseMessage.builder().responses(responses).build();
            }
        };
    }

    // -------------------------------------------------------------------------
    // On-disk shape
    // -------------------------------------------------------------------------

    record SessionFile(String id, String userId, String createdAt, String expiresAt,
                       Map<String, Object> metadata, long eventVersion, List<EventEntry> events) {

        SessionFile withEvents(List<EventEntry> newEvents) {
            return new SessionFile(id, userId, createdAt, expiresAt, metadata, eventVersion + 1, newEvents);
        }
    }

    record EventEntry(String id, String timestamp, String messageType, String text,
                      String messageData, boolean archived, String branch, Map<String, Object> metadata) {
    }
}
