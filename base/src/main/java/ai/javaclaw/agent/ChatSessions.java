package ai.javaclaw.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAlreadyExistsException;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The sessions of every conversation. A conversation ({@link ConversationKey}) can have several
 * sessions; exactly one is active, the one that became active last. Everything about a session
 * lives in its Spring AI session metadata, next to its {@link SessionKind} and {@link SessionChannel}:
 * <ul>
 *     <li>{@value #CONVERSATION}: the conversation key</li>
 *     <li>{@value #TITLE}: absent until the session is titled</li>
 *     <li>{@value #ACTIVATED_AT}: when the session last became active</li>
 * </ul>
 * All changes to one conversation are serialized, as each is a read, a change and a save.
 */
@Component
public class ChatSessions {

    /** The session metadata attribute that holds the conversation key. */
    public static final String CONVERSATION = "conversation";
    /** The session metadata attribute that holds the title. */
    public static final String TITLE = "title";
    /** The session metadata attribute that holds when the session last became active. */
    public static final String ACTIVATED_AT = "activatedAt";

    /** What an untitled session is called. */
    public static final String UNTITLED = "New chat";
    /** The longest title kept. */
    public static final int MAX_TITLE_LENGTH = 60;

    /** The {@link SessionAttributes#AGENT_NAME} every JavaClaw session is tagged with. */
    static final String AGENT_NAME = "javaclaw";

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public ChatSessions(SessionService sessionService, SessionRepository sessionRepository) {
        this(sessionService, sessionRepository, Clock.systemUTC());
    }

    ChatSessions(SessionService sessionService, SessionRepository sessionRepository, Clock clock) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    /** One session of a conversation, as a list shows it. */
    public record Summary(String id, String title, SessionChannel channel, Instant lastActivity, boolean active) {}

    /**
     * The id of the conversation's active session; the first use of a conversation creates it.
     *
     * @param kind    what the session is for, if one is created
     * @param channel where the session came from, if one is created
     */
    public String active(ConversationKey key, SessionKind kind, SessionChannel channel) {
        return locked(key, () -> {
            List<Session> sessions = sessions(key);
            return sessions.isEmpty() ? newSession(key, kind, channel, null).id() : active(sessions).id();
        });
    }

    /**
     * Starts a new session in the conversation and makes it active. The previous one is kept.
     *
     * @param title the new session's title; null or blank leaves it untitled
     * @return the new session's id
     */
    public String create(ConversationKey key, SessionKind kind, SessionChannel channel, String title) {
        return locked(key, () -> {
            List<Session> sessions = adoptLegacy(key);
            String cleaned = cleanTitle(title);
            return newSession(key, kind, channel, cleaned == null ? null : uniqueTitle(sessions, cleaned, null)).id();
        });
    }

    /**
     * Makes an earlier session of the conversation active again.
     *
     * @throws IllegalArgumentException if the session is not one of this conversation's
     */
    public void switchTo(ConversationKey key, String sessionId) {
        locked(key, () -> save(sessionOf(adoptLegacy(key), key, sessionId), ACTIVATED_AT, clock.instant().toString()));
    }

    /**
     * Sets a session's title, made unique in its conversation.
     *
     * @throws IllegalArgumentException if the title is blank or the session is not one of this
     *                                  conversation's
     */
    public void rename(ConversationKey key, String sessionId, String title) {
        String cleaned = cleanTitle(title);
        if (cleaned == null) {
            throw new IllegalArgumentException("A title cannot be blank");
        }
        locked(key, () -> {
            List<Session> sessions = adoptLegacy(key);
            return save(sessionOf(sessions, key, sessionId), TITLE, uniqueTitle(sessions, cleaned, sessionId));
        });
    }

    /**
     * Titles a session that has no title yet; a session that has one keeps it.
     *
     * @return whether the title was set
     */
    boolean titleIfUntitled(ConversationKey key, String sessionId, String title) {
        String cleaned = cleanTitle(title);
        if (cleaned == null) {
            return false;
        }
        return locked(key, () -> {
            List<Session> sessions = adoptLegacy(key);
            Session session = sessionOf(sessions, key, sessionId);
            if (title(session) != null) {
                return false;
            }
            save(session, TITLE, uniqueTitle(sessions, cleaned, sessionId));
            return true;
        });
    }

    /** The conversation's sessions, latest activity first. */
    public List<Summary> list(ConversationKey key) {
        List<Session> sessions = sessions(key);
        if (sessions.isEmpty()) {
            return List.of();
        }
        String activeId = active(sessions).id();
        return sessions.stream()
                .map(session -> summary(session, session.id().equals(activeId)))
                .sorted(Comparator.comparing(Summary::lastActivity).reversed())
                .toList();
    }

    /**
     * The active session of every chat conversation other than {@code except}, latest activity
     * first. Scheduled tasks' conversations are left out.
     */
    public List<Summary> otherConversations(ConversationKey except) {
        Map<String, List<Session>> byConversation = new LinkedHashMap<>();
        for (Session session : sessionService.findByMetadata(SessionAttributes.AGENT_NAME, AGENT_NAME)) {
            if (!isChat(session)) {
                continue;
            }
            Object conversation = session.metadata().get(CONVERSATION);
            if (conversation == null) {
                // Legacy: a session from before conversations had several is its conversation's only one
                if (!session.id().equals(except.legacySessionId())) {
                    byConversation.put("legacy:" + session.id(), List.of(session));
                }
            } else if (!except.value().equals(conversation)) {
                byConversation.computeIfAbsent(conversation.toString(), _ -> new ArrayList<>()).add(session);
            }
        }
        return byConversation.values().stream()
                .map(sessions -> summary(active(sessions), true))
                .sorted(Comparator.comparing(Summary::lastActivity).reversed())
                .toList();
    }

    /** One chat session, active or not, as {@link #list} or {@link #otherConversations} shows it. */
    public Summary summary(String sessionId) {
        Session session = sessionService.findById(sessionId);
        if (session == null || !isChat(session)) {
            throw new IllegalArgumentException("No chat session " + sessionId);
        }
        return summary(session, false);
    }

    /** The session's title, or null when it has none. */
    static String title(Session session) {
        Object title = session.metadata().get(TITLE);
        return title == null || title.toString().isBlank() ? null : title.toString();
    }

    /** Whether the session was started before conversations could have several sessions. */
    static boolean isLegacy(Session session) {
        return !session.metadata().containsKey(CONVERSATION);
    }

    /**
     * A title as it is kept: one line, single spaces, no surrounding quotes and no trailing
     * period, at most {@value #MAX_TITLE_LENGTH} characters.
     *
     * @return the title, null when nothing is left
     */
    static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String cleaned = title.strip().lines().filter(line -> !line.isBlank()).findFirst().orElse("")
                .replaceAll("\\s+", " ").strip();
        cleaned = stripQuotes(cleaned).replaceAll("\\.+$", "").strip();
        cleaned = stripQuotes(cleaned);
        if (cleaned.length() > MAX_TITLE_LENGTH) {
            int cut = Character.isHighSurrogate(cleaned.charAt(MAX_TITLE_LENGTH - 1)) ? MAX_TITLE_LENGTH - 1 : MAX_TITLE_LENGTH;
            cleaned = cleaned.substring(0, cut).strip();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String stripQuotes(String text) {
        return text.replaceAll("^[\"'`“”‘’«»*_]+|[\"'`“”‘’«»*_]+$", "").strip();
    }

    /** A session's first user message, or null if it has none yet. */
    String firstUserMessage(String sessionId) {
        return messages(sessionId).stream()
                .filter(UserMessage.class::isInstance)
                .map(Message::getText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** All messages of a session, in order. */
    List<Message> messages(String sessionId) {
        return sessionService.getMessages(sessionId);
    }

    /** The session, or null when there is none. */
    Session find(String sessionId) {
        return sessionService.findById(sessionId);
    }

    // --- Conversations ---------------------------------------------------------------------------

    /**
     * The conversation's sessions. A conversation none of whose sessions are tagged with it is the
     * legacy session with its old fixed id, if there is one.
     */
    private List<Session> sessions(ConversationKey key) {
        List<Session> sessions = sessionRepository.findByMetadata(CONVERSATION, key.value());
        if (!sessions.isEmpty()) {
            return sessions;
        }
        Session legacy = legacySession(key);
        return legacy == null ? List.of() : List.of(legacy);
    }

    /**
     * Legacy: the session a conversation had when each conversation had one session, under the
     * fixed id derived from its key. It belongs to the conversation as long as no session is
     * tagged with the conversation; {@link #adoptLegacy} tags it on its first change.
     */
    private Session legacySession(ConversationKey key) {
        Session session = sessionService.findById(key.legacySessionId());
        return session != null && isLegacy(session) ? session : null;
    }

    /**
     * Legacy: before a conversation's sessions change, tags its legacy session with the
     * conversation, so it stays one of them next to sessions created from now on.
     *
     * @return the conversation's sessions
     */
    private List<Session> adoptLegacy(ConversationKey key) {
        List<Session> sessions = sessions(key);
        if (sessions.size() == 1 && isLegacy(sessions.getFirst())) {
            Session legacy = sessions.getFirst();
            return List.of(save(copy(legacy)
                    .metadata(CONVERSATION, key.value())
                    .metadata(ACTIVATED_AT, legacy.createdAt().toString())
                    .build()));
        }
        return sessions;
    }

    private static Session sessionOf(List<Session> sessions, ConversationKey key, String sessionId) {
        return sessions.stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Session " + sessionId + " is not one of " + key + "'s"));
    }

    /** The session that became active last. */
    private static Session active(List<Session> sessions) {
        return sessions.stream()
                .max(Comparator.comparing(ChatSessions::activatedAt)
                        .thenComparing(Session::createdAt)
                        .thenComparing(Session::id))
                .orElseThrow();
    }

    private static Instant activatedAt(Session session) {
        Object value = session.metadata().get(ACTIVATED_AT);
        if (value != null) {
            try {
                return Instant.parse(value.toString());
            } catch (DateTimeParseException ignored) {
                // fall through to the creation time
            }
        }
        return session.createdAt();
    }

    private Session newSession(ConversationKey key, SessionKind kind, SessionChannel channel, String title) {
        Instant now = clock.instant();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SessionAttributes.AGENT_NAME, AGENT_NAME);
        metadata.put(SessionKind.ATTRIBUTE, kind.value());
        metadata.put(SessionChannel.ATTRIBUTE, channel.value());
        metadata.put(CONVERSATION, key.value());
        metadata.put(ACTIVATED_AT, now.toString());
        if (title != null) {
            metadata.put(TITLE, title);
        }
        while (true) {
            try {
                return sessionService.create(CreateSessionRequest.builder().id(newId(now)).metadata(metadata).build());
            } catch (SessionAlreadyExistsException e) {
                // Eight random hex characters collided within the same second: draw again
            }
        }
    }

    /** A new session id: its creation time and eight random hex characters, like {@code 20261124_153012_a1b2c3d4}. */
    private static String newId(Instant now) {
        return ID_TIME.format(now) + "_" + "%08x".formatted(ThreadLocalRandom.current().nextInt());
    }

    /** {@code title}, or {@code title #2}, {@code title #3}… when another session of the conversation has it. */
    private static String uniqueTitle(List<Session> sessions, String title, String exceptSessionId) {
        Set<String> taken = new HashSet<>();
        sessions.stream()
                .filter(session -> !session.id().equals(exceptSessionId))
                .map(ChatSessions::title)
                .filter(existing -> existing != null)
                .forEach(taken::add);
        String unique = title;
        for (int n = 2; taken.contains(unique); n++) {
            unique = title + " #" + n;
        }
        return unique;
    }

    // --- Summaries -------------------------------------------------------------------------------

    private Summary summary(Session session, boolean active) {
        String title = title(session);
        if (title == null && isLegacy(session)) {
            // Legacy sessions were never titled; their first message says what they are about
            title = cleanTitle(firstUserMessage(session.id()));
        }
        return new Summary(session.id(), title == null ? UNTITLED : title, channel(session), lastActivity(session), active);
    }

    private Instant lastActivity(Session session) {
        List<SessionEvent> last = sessionService.getEvents(session.id(), EventFilter.lastN(1));
        return last.isEmpty() ? session.createdAt() : last.getFirst().getTimestamp();
    }

    private static SessionChannel channel(Session session) {
        Object value = session.metadata().get(SessionChannel.ATTRIBUTE);
        for (SessionChannel channel : SessionChannel.values()) {
            if (channel.value().equals(value)) {
                return channel;
            }
        }
        return null;
    }

    /** A session created before sessions had a kind is a chat, unless its id is a task's. */
    static boolean isChat(Session session) {
        Object kind = session.metadata().get(SessionKind.ATTRIBUTE);
        return kind == null
                ? !session.id().startsWith(SessionKind.TASK_ID_PREFIX)
                : SessionKind.CHAT.value().equals(kind);
    }

    // --- Storage ---------------------------------------------------------------------------------

    private Session save(Session session, String attribute, String value) {
        return save(copy(session).metadata(attribute, value).build());
    }

    /** An upsert, which also rebuilds the session's index rows. */
    private Session save(Session session) {
        return sessionRepository.save(session);
    }

    private static Session.Builder copy(Session session) {
        return Session.builder()
                .id(session.id())
                .createdAt(session.createdAt())
                .expiresAt(session.expiresAt())
                .metadata(session.metadata());
    }

    private <T> T locked(ConversationKey key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key.value(), _ -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
