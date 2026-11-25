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
import java.util.regex.Pattern;

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
    public static final int MAX_TITLE_LENGTH = 50;

    /** The {@link SessionAttributes#AGENT_NAME} every JavaClaw session is tagged with. */
    static final String AGENT_NAME = "javaclaw";

    private static final Pattern TITLE_PREFIX = Pattern.compile("^title\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SURROUNDING_QUOTES = Pattern.compile("^[\"'`“”„‘’‚«»‹›]+|[\"'`“”„‘’‚«»‹›]+$");
    private static final String OPENING_BRACKETS = "([{<";
    private static final String CLOSING_BRACKETS = ")]}>";

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
            List<Session> sessions = sessions(key);
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
        locked(key, () -> save(sessionOf(sessions(key), key, sessionId), ACTIVATED_AT, clock.instant().toString()));
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
            List<Session> sessions = sessions(key);
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
            List<Session> sessions = sessions(key);
            Session session = sessionOf(sessions, key, sessionId);
            if (title(session) != null) {
                return false;
            }
            save(session, TITLE, uniqueTitle(sessions, cleaned, sessionId));
            return true;
        });
    }

    /** Whether the session is one of the conversation's. */
    public boolean isSessionOf(ConversationKey key, String sessionId) {
        Session session = sessionId == null || sessionId.isBlank() ? null : sessionService.findById(sessionId);
        return session != null && key.value().equals(session.metadata().get(CONVERSATION));
    }

    /**
     * One session of the conversation, as {@link #list} shows it.
     *
     * @throws IllegalArgumentException if the session is not one of this conversation's
     */
    public Summary summary(ConversationKey key, String sessionId) {
        return list(key).stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Session " + sessionId + " is not one of " + key + "'s"));
    }

    /** The conversation's sessions, latest activity first. */
    public List<Summary> list(ConversationKey key) {
        List<Session> sessions = sessions(key);
        if (sessions.isEmpty()) {
            return List.of();
        }
        String activeId = active(sessions).id();
        return sessions.stream()
                .map(session -> toSummary(session, session.id().equals(activeId)))
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
            if (conversation != null && !except.value().equals(conversation)) {
                byConversation.computeIfAbsent(conversation.toString(), _ -> new ArrayList<>()).add(session);
            }
        }
        return byConversation.values().stream()
                .map(sessions -> toSummary(active(sessions), true))
                .sorted(Comparator.comparing(Summary::lastActivity).reversed())
                .toList();
    }

    /** One chat session, active or not, as {@link #list} or {@link #otherConversations} shows it. */
    public Summary summary(String sessionId) {
        Session session = sessionService.findById(sessionId);
        if (session == null || !isChat(session)) {
            throw new IllegalArgumentException("No chat session " + sessionId);
        }
        return toSummary(session, false);
    }

    /** The session's title, or null when it has none. */
    static String title(Session session) {
        Object title = session.metadata().get(TITLE);
        return title == null || title.toString().isBlank() ? null : title.toString();
    }

    /**
     * A title as it is kept, whether the model or a person gave it: the first non-blank line,
     * single spaces, no {@code Title:} prefix, no surrounding quotes or brackets, no trailing
     * period, at most {@value #MAX_TITLE_LENGTH} characters cut at a whole word.
     *
     * @return the title, null when nothing is left
     */
    static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String cleaned = title.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("")
                .replaceAll("\\s+", " ");
        cleaned = TITLE_PREFIX.matcher(cleaned).replaceFirst("");
        cleaned = unwrap(cleaned);
        cleaned = cleaned.replaceAll("\\.+$", "").strip();
        cleaned = unwrap(cleaned);
        cleaned = cutAtWord(cleaned);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * {@code text} without the quotes or brackets around it. Quotes go from either end; brackets
     * only as a pair that encloses the whole text, so {@code Echo (tab B)} keeps its parentheses.
     */
    private static String unwrap(String text) {
        String unwrapped = text;
        while (true) {
            String stripped = SURROUNDING_QUOTES.matcher(unwrapped).replaceAll("").strip();
            if (encloses(stripped)) {
                stripped = stripped.substring(1, stripped.length() - 1).strip();
            }
            if (stripped.equals(unwrapped)) {
                return unwrapped;
            }
            unwrapped = stripped;
        }
    }

    /** Whether the text's first and last characters are a pair of brackets around all of it. */
    private static boolean encloses(String text) {
        if (text.length() < 2) {
            return false;
        }
        int kind = OPENING_BRACKETS.indexOf(text.charAt(0));
        if (kind < 0 || CLOSING_BRACKETS.indexOf(text.charAt(text.length() - 1)) != kind) {
            return false;
        }
        char open = OPENING_BRACKETS.charAt(kind);
        char close = CLOSING_BRACKETS.charAt(kind);
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0 && i < text.length() - 1) {
                return false; // the first bracket closes before the end: "(a) and (b)"
            }
        }
        return depth == 0;
    }

    /** {@code text} if it fits in {@value #MAX_TITLE_LENGTH} characters, else cut at the last whole word that does. */
    private static String cutAtWord(String text) {
        if (text.length() <= MAX_TITLE_LENGTH) {
            return text;
        }
        int space = text.lastIndexOf(' ', MAX_TITLE_LENGTH);
        if (space > 0) {
            return text.substring(0, space).strip();
        }
        // One word longer than a title: cut it, but never between the two halves of a surrogate pair
        int cut = Character.isHighSurrogate(text.charAt(MAX_TITLE_LENGTH - 1)) ? MAX_TITLE_LENGTH - 1 : MAX_TITLE_LENGTH;
        return text.substring(0, cut);
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

    /** The conversation's sessions. */
    private List<Session> sessions(ConversationKey key) {
        return sessionRepository.findByMetadata(CONVERSATION, key.value());
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
        return Instant.parse(session.metadata().get(ACTIVATED_AT).toString());
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

    private Summary toSummary(Session session, boolean active) {
        String title = title(session);
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

    /** Whether the session is a conversation with the owner rather than a scheduled task's. */
    static boolean isChat(Session session) {
        return SessionKind.CHAT.value().equals(session.metadata().get(SessionKind.ATTRIBUTE));
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
