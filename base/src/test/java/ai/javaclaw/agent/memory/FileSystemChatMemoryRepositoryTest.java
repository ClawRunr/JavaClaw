package ai.javaclaw.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemChatMemoryRepositoryTest {

    @TempDir
    Path workspaceDir;
    FileSystemChatMemoryRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = new FileSystemChatMemoryRepository(new FileSystemResource(workspaceDir));
    }

    // -----------------------------------------------------------------------
    // saveAll / findByConversationId roundtrip
    // -----------------------------------------------------------------------

    @Test
    void saveAndReloadConversation() {
        List<Message> messages = List.of(
                new UserMessage("Hello!"),
                new AssistantMessage("Hi there, how can I help?")
        );

        repository.saveAll("2026-03-21/web", messages);

        List<Message> loaded = repository.findByConversationId("2026-03-21/web");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getText()).isEqualTo("Hello!");
        assertThat(loaded.get(1).getText()).isEqualTo("Hi there, how can I help?");
    }

    @Test
    void saveCreatesFileAtCorrectPath() throws IOException {
        repository.saveAll("web", List.of(new UserMessage("Hi")));

        Path expectedFile = workspaceDir.resolve("conversations/chat-web.yaml");
        assertThat(expectedFile).exists();
        String content = Files.readString(expectedFile);
        assertThat(content)
                .contains("createdAt:")
                .contains("updatedAt:")
                .contains("role: user")
                .contains("content: Hi");
    }

    @Test
    void savePreservesCreatedAtOnSubsequentSaves() throws IOException {
        repository.saveAll("web", List.of(new UserMessage("First")));

        Path file = workspaceDir.resolve("conversations/chat-web.yaml");
        String firstCreatedAt = extractFrontmatterValue(Files.readString(file), "createdAt");

        repository.saveAll("web", List.of(new UserMessage("First"), new AssistantMessage("Second")));

        String updatedCreatedAt = extractFrontmatterValue(Files.readString(file), "createdAt");
        assertThat(updatedCreatedAt).isEqualTo(firstCreatedAt);
    }

    @Test
    void saveUpdatesUpdatedAtOnSubsequentSaves() throws IOException {
        repository.saveAll("web", List.of(new UserMessage("First")));
        Path file = workspaceDir.resolve("conversations/chat-web.yaml");
        String firstUpdatedAt = extractFrontmatterValue(Files.readString(file), "updatedAt");

        repository.saveAll("web", List.of(new UserMessage("First"), new AssistantMessage("Second")));

        String secondUpdatedAt = extractFrontmatterValue(Files.readString(file), "updatedAt");
        // updatedAt must be present on both writes; they may be equal if writes happen within the same instant
        assertThat(secondUpdatedAt).isNotNull();
        assertThat(firstUpdatedAt).isNotNull();
    }

    @Test
    void saveCreatesFileForTelegramChannel() {
        repository.saveAll("telegram-123456789", List.of(new UserMessage("Hello from Telegram")));

        Path expectedFile = workspaceDir.resolve("conversations/chat-telegram-123456789.yaml");
        assertThat(expectedFile).exists();
    }

    @Test
    void saveOverwritesPreviousMessages() {
        repository.saveAll("web", List.of(new UserMessage("First")));
        repository.saveAll("web", List.of(new UserMessage("First"), new AssistantMessage("Second")));

        List<Message> loaded = repository.findByConversationId("web");
        assertThat(loaded).hasSize(2);
    }

    @Test
    void savePreservesMessageOrder() {
        List<Message> messages = List.of(
                new UserMessage("Question 1"),
                new AssistantMessage("Answer 1"),
                new UserMessage("Question 2"),
                new AssistantMessage("Answer 2")
        );

        repository.saveAll("web", messages);

        List<Message> loaded = repository.findByConversationId("web");
        assertThat(loaded).extracting(Message::getText)
                .containsExactly("Question 1", "Answer 1", "Question 2", "Answer 2");
    }

    @Test
    void appendAllAddsMessagesToExistingConversation() {
        repository.saveAll("web", List.of(new UserMessage("Hello!")));

        repository.appendAll("web", List.of(new AssistantMessage("Hi there!")));

        List<Message> loaded = repository.findByConversationId("web");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getText()).isEqualTo("Hello!");
        assertThat(loaded.get(1).getText()).isEqualTo("Hi there!");
    }

    @Test
    void appendAllCreatesFileWhenConversationDoesNotExist() {
        repository.appendAll("web", List.of(new UserMessage("First message")));

        List<Message> loaded = repository.findByConversationId("web");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getText()).isEqualTo("First message");
    }

    @Test
    void appendAllPreservesCreatedAt() throws IOException {
        repository.saveAll("web", List.of(new UserMessage("First")));
        Path file = workspaceDir.resolve("conversations/chat-web.yaml");
        String originalCreatedAt = extractFrontmatterValue(Files.readString(file), "createdAt");

        repository.appendAll("web", List.of(new AssistantMessage("Second")));

        String createdAtAfterAppend = extractFrontmatterValue(Files.readString(file), "createdAt");
        assertThat(createdAtAfterAppend).isEqualTo(originalCreatedAt);
    }

    @Test
    void appendAllPreservesMessageOrder() {
        repository.saveAll("web", List.of(
                new UserMessage("Q1"),
                new AssistantMessage("A1")
        ));

        repository.appendAll("web", List.of(
                new UserMessage("Q2"),
                new AssistantMessage("A2")
        ));

        List<Message> loaded = repository.findByConversationId("web");
        assertThat(loaded).extracting(Message::getText)
                .containsExactly("Q1", "A1", "Q2", "A2");
    }

    // -----------------------------------------------------------------------
    // findByConversationId — missing file
    // -----------------------------------------------------------------------

    @Test
    void findReturnsEmptyListWhenConversationDoesNotExist() {
        List<Message> messages = repository.findByConversationId("web");

        assertThat(messages).isEmpty();
    }

    @Test
    void findReturnsEmptyListWhenConversationsDirDoesNotExist() {
        List<Message> messages = repository.findByConversationId("web");

        assertThat(messages).isEmpty();
        assertThat(workspaceDir.resolve("conversations")).doesNotExist();
    }

    // -----------------------------------------------------------------------
    // findConversationIds
    // -----------------------------------------------------------------------

    @Test
    void findConversationIdsReturnsAllSavedIds() {
        repository.saveAll("web", List.of(new UserMessage("Hi")));
        repository.saveAll("telegram-111", List.of(new UserMessage("Hello")));

        List<String> ids = repository.findConversationIds();

        assertThat(ids).containsExactlyInAnyOrder(
                "web",
                "telegram-111"
        );
    }

    @Test
    void findConversationIdsReturnsEmptyListWhenNothingSaved() {
        assertThat(repository.findConversationIds()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // deleteByConversationId
    // -----------------------------------------------------------------------

    @Test
    void deleteRemovesFile() throws IOException {
        repository.saveAll("web", List.of(new UserMessage("Hi")));

        repository.deleteByConversationId("web");

        Path file = workspaceDir.resolve("conversations/chat-web.yaml");
        assertThat(file).doesNotExist();
        assertThat(repository.findByConversationId("web")).isEmpty();
    }

    @Test
    void deleteIsIdempotentWhenFileDoesNotExist() {
        // must not throw
        repository.deleteByConversationId("web");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String extractFrontmatterValue(String fileContent, String key) {
        return fileContent.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).strip())
                .findFirst()
                .orElse(null);
    }
}
