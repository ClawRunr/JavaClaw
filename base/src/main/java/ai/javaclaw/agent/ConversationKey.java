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

    private static final ConversationKey WEB = new ConversationKey("web");

    private static final String TASK_FILE_EXTENSION = ".md";
    /** The character set task file names are already restricted to by {@code FileSystemTaskRepository}. */
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("_{2,}");

    private final String value;

    private ConversationKey(String value) {
        this.value = value;
    }

    /** The web chat, at {@code /chat} and {@code /ui}: there is one. */
    public static ConversationKey web() {
        return WEB;
    }

    /** A Telegram chat, or one topic of it when {@code threadId} is not null. */
    public static ConversationKey telegram(long chatId, Integer threadId) {
        return new ConversationKey("telegram:" + chatId + (threadId == null ? "" : ":" + threadId));
    }

    /** A Discord channel, a direct message channel included. */
    public static ConversationKey discord(String channelId) {
        return new ConversationKey("discord:" + require(channelId));
    }

    /** A WhatsApp chat, by its JID. */
    public static ConversationKey whatsApp(String jid) {
        return new ConversationKey("whatsapp:" + require(jid));
    }

    /** A Microsoft Teams conversation. */
    public static ConversationKey teams(String conversationId) {
        return new ConversationKey("teams:" + require(conversationId));
    }

    /**
     * The conversation a task runs in, by the task's id (the absolute path of its file).
     *
     * <p>The key is built from the file's last two path segments, not the whole path, so it does not
     * depend on where the workspace is. Tasks are stored as {@code {date}/{time}-{name}.md} and
     * recurring ones as {@code recurring/{name}.md}, so the pair is unique and stays readable:
     * {@code task:recurring-daily-summary}. Characters outside the set task file names already use
     * are replaced.
     *
     * @throws IllegalArgumentException if the task id names no file
     */
    public static ConversationKey task(String taskId) {
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
        String readable = group.isBlank() ? name : group + "-" + name;
        return new ConversationKey("task:" + REPEATED_SEPARATORS.matcher(UNSAFE_CHARACTERS.matcher(readable).replaceAll("_")).replaceAll("_"));
    }

    /** The key as stored in session metadata. */
    public String value() {
        return value;
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
}
