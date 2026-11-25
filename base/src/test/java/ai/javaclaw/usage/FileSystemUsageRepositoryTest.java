package ai.javaclaw.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemUsageRepositoryTest {

    @TempDir
    Path workspaceDir;

    FileSystemUsageRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = new FileSystemUsageRepository(new FileSystemResource(workspaceDir));
    }

    @Test
    void saveWritesFrontmatterFileUnderDatedDirectory() throws IOException {
        Instant recordedAt = Instant.parse("2026-11-25T14:30:52.123Z");
        UsageRecord record = new UsageRecord(
                recordedAt,
                1200,
                350,
                1550,
                "claude-sonnet-4-6",
                "anthropic",
                UsageSource.TELEGRAM,
                "telegram-123",
                null
        );

        repository.save(record);

        Path dayDir = workspaceDir.resolve("usage/2026-11-25");
        assertThat(dayDir).isDirectory();
        List<Path> files;
        try (Stream<Path> stream = Files.list(dayDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).toList();
        }
        assertThat(files).hasSize(1);

        UsageRecord loaded = repository.read(files.getFirst());
        assertThat(loaded.recordedAt()).isEqualTo(recordedAt);
        assertThat(loaded.promptTokens()).isEqualTo(1200);
        assertThat(loaded.completionTokens()).isEqualTo(350);
        assertThat(loaded.totalTokens()).isEqualTo(1550);
        assertThat(loaded.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(loaded.provider()).isEqualTo("anthropic");
        assertThat(loaded.source()).isEqualTo(UsageSource.TELEGRAM);
        assertThat(loaded.conversationId()).isEqualTo("telegram-123");
        assertThat(loaded.taskId()).isNull();
    }

    @Test
    void saveIncludesTaskIdForScheduledTasks() throws IOException {
        UsageRecord record = new UsageRecord(
                Instant.parse("2026-11-25T10:00:00Z"),
                10,
                5,
                15,
                "gpt-5.4",
                "openai",
                UsageSource.SCHEDULED_TASK,
                "task-2026-11-25-100000-buy_milk",
                "task-2026-11-25-100000-buy_milk"
        );

        repository.save(record);

        Path dayDir = workspaceDir.resolve("usage/2026-11-25");
        Path file;
        try (Stream<Path> stream = Files.list(dayDir)) {
            file = stream.findFirst().orElseThrow();
        }

        String content = Files.readString(file);
        assertThat(content).contains("taskId: task-2026-11-25-100000-buy_milk");

        UsageRecord loaded = repository.read(file);
        assertThat(loaded.taskId()).isEqualTo("task-2026-11-25-100000-buy_milk");
        assertThat(loaded.source()).isEqualTo(UsageSource.SCHEDULED_TASK);
    }

    @Test
    void recordsSurviveRestartWithNewRepositoryInstance() throws IOException {
        repository.save(new UsageRecord(
                Instant.parse("2026-11-25T12:00:00Z"),
                1,
                2,
                3,
                "model",
                "ollama",
                UsageSource.WEB_CHAT,
                "web",
                null
        ));

        FileSystemUsageRepository reloaded = new FileSystemUsageRepository(new FileSystemResource(workspaceDir));
        Path dayDir = workspaceDir.resolve("usage/2026-11-25");
        Path file;
        try (Stream<Path> stream = Files.list(dayDir)) {
            file = stream.findFirst().orElseThrow();
        }

        UsageRecord loaded = reloaded.read(file);
        assertThat(loaded.promptTokens()).isEqualTo(1);
        assertThat(loaded.completionTokens()).isEqualTo(2);
        assertThat(loaded.conversationId()).isEqualTo("web");
    }
}
