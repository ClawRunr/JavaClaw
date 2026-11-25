package ai.javaclaw.agent;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Which conversation a message belongs to: one web chat, one Telegram chat (or topic), one Discord
 * channel, and so on. Stable for as long as that chat exists; a conversation can have several
 * sessions (see {@link ChatSessions}).
 *
 * <p>Built only by the factory methods, one per channel. Keys are written to session metadata and
 * compared, never parsed.
 */
public final class ConversationKey {

    private static final ConversationKey WEB = new ConversationKey("web", "web");

    private static final String TASK_FILE_EXTENSION = ".md";
    /** The character set task file names are already restricted to by {@code FileSystemTaskRepository}. */
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("_{2,}");

    private final String value;
    private final String legacySessionId;

    private ConversationKey(String value, String legacySessionId) {
        this.value = value;
        this.legacySessionId = legacySessionId;
    }

    /** The web chat, at {@code /chat} and {@code /ui}: there is one. */
    public static ConversationKey web() {
        return WEB;
    }

    /** A Telegram chat, or one topic of it when {@code threadId} is not null. */
    public static ConversationKey telegram(long chatId, Integer threadId) {
        return threadId == null
                ? new ConversationKey("telegram:" + chatId, "telegram-" + chatId)
                : new ConversationKey("telegram:" + chatId + ":" + threadId, "telegram-" + chatId + "-" + threadId);
    }

    /** A Discord channel, a direct message channel included. */
    public static ConversationKey discord(String channelId) {
        return new ConversationKey("discord:" + require(channelId), "discord-" + channelId);
    }

    /** A WhatsApp chat, by its JID. */
    public static ConversationKey whatsApp(String jid) {
        return new ConversationKey("whatsapp:" + require(jid), jid);
    }

    /** A Microsoft Teams conversation. */
    public static ConversationKey teams(String conversationId) {
        return new ConversationKey("teams:" + require(conversationId), "teams-" + conversationId);
    }

    /** The conversation a task runs in, by the task's id (the absolute path of its file). */
    public static ConversationKey task(String taskId) {
        return new ConversationKey("task:" + require(taskId), legacyTaskSessionId(taskId));
    }

    /** The key as stored in session metadata. */
    public String value() {
        return value;
    }

    /**
     * Legacy: the fixed id this conversation's one session had before conversations could have
     * several. Read only by {@link ChatSessions}.
     */
    String legacySessionId() {
        return legacySessionId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConversationKey key && value.equals(key.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static String require(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A conversation needs an id");
        }
        return id;
    }

    /**
     * Legacy: the session id a task ran in before sessions had generated ids.
     *
     * <p>A task's id is the absolute path of its markdown file. The last two path segments carry
     * everything needed: tasks are stored as {@code {date}/{time}-{name}.md} and recurring ones as
     * {@code recurring/{name}.md}, so the pair is unique across days and stays readable.
     *
     * @throws IllegalArgumentException if the task id names no file
     */
    private static String legacyTaskSessionId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a conversation from an empty task id");
        }
        Path taskFile = Path.of(taskId).normalize();
        Path fileName = taskFile.getFileName();
        Path parent = taskFile.getParent();
        if (fileName == null) {
            throw new IllegalArgumentException("Cannot derive a conversation from a task id that names no file");
        }
        String name = fileName.toString();
        if (name.endsWith(TASK_FILE_EXTENSION)) {
            name = name.substring(0, name.length() - TASK_FILE_EXTENSION.length());
        }
        String group = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (name.isBlank() && group.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a conversation from a task id that names no file");
        }
        String id = SessionKind.TASK_ID_PREFIX + (group.isBlank() ? name : group + "-" + name);
        return REPEATED_SEPARATORS.matcher(UNSAFE_CHARACTERS.matcher(id).replaceAll("_")).replaceAll("_");
    }
}
