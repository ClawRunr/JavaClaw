package ai.javaclaw.agent.memory;

import ai.javaclaw.files.YamlDocument;
import ai.javaclaw.files.YamlParser;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Persists chat conversation history as YAML files inside the agent workspace.
 *
 * <p>The conversation ID is the channel name, e.g. {@code web} or
 * {@code telegram-123456789}. The repository maps this to a flat file:
 * {@code {workspace}/conversations/chat-{channel}.yaml}
 *
 * <p>Each file has a frontmatter block with timestamps and a body containing the
 * message list:
 * <pre>
 * ---
 * createdAt: 2026-03-21T10:00:00Z
 * updatedAt: 2026-03-21T10:05:30Z
 * ---
 * - user: |
 *     Question text
 * - assistant: |
 *     Answer text
 * </pre>
 */
@Component
public class FileSystemChatMemoryRepository implements ChatMemoryRepository {

    private final Path conversationsDir;

    public FileSystemChatMemoryRepository(@Value("${agent.workspace:Unknown}") Resource workspaceDir) throws IOException {
        this.conversationsDir = workspaceDir.getFilePath().resolve("conversations");
    }

    @Override
    public List<String> findConversationIds() {
        if (!Files.exists(conversationsDir)) return List.of();
        try (Stream<Path> files = Files.list(conversationsDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("chat-") && name.endsWith(".yaml"))
                    .map(name -> name.substring("chat-".length(), name.length() - ".yaml".length()))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list conversations", e);
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Path file = resolveFile(conversationId);
        if (!Files.exists(file)) return List.of();
        try {
            YamlDocument doc = YamlParser.parse(Files.readString(file));
            return ChatYamlSerializer.deserialize(doc.body());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read conversation: " + conversationId, e);
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Path file = resolveFile(conversationId);
        ensureDirectory(file.getParent());

        // Preserve createdAt from any existing file; set it fresh on first write
        String createdAt = Instant.now().toString();
        if (Files.exists(file)) {
            try {
                Map<String, String> existing = YamlParser.parse(Files.readString(file)).frontmatter();
                if (existing.containsKey("createdAt")) createdAt = existing.get("createdAt");
            } catch (IOException ignored) {}
        }

        Map<String, String> frontmatter = new LinkedHashMap<>();
        frontmatter.put("createdAt", createdAt);
        frontmatter.put("updatedAt", Instant.now().toString());

        String body = ChatYamlSerializer.serialize(messages);
        String content = YamlParser.serialize(new YamlDocument(frontmatter, body));
        try {
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save conversation: " + conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            Files.deleteIfExists(resolveFile(conversationId));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete conversation: " + conversationId, e);
        }
    }

    /**
     * Maps a conversation ID (channel name) to
     * {@code conversations/chat-{channel}.yaml}.
     */
    private Path resolveFile(String conversationId) {
        return conversationsDir.resolve("chat-" + conversationId + ".yaml");
    }

    private static Path ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }
}
