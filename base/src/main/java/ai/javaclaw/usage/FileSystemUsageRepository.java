package ai.javaclaw.usage;

import ai.javaclaw.files.YamlDocument;
import ai.javaclaw.files.YamlParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores each usage record as a YAML frontmatter file under
 * {@code {workspace}/usage/yyyy-MM-dd/HHmmss-SSS-<n>.yaml}.
 */
@Component
public class FileSystemUsageRepository implements UsageRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss-SSS");

    private final Path usageDir;
    private final AtomicInteger sequence = new AtomicInteger();

    public FileSystemUsageRepository(@Value("${agent.workspace:Unknown}") Resource workspaceDir) throws IOException {
        this.usageDir = workspaceDir.getFilePath().resolve("usage");
    }

    @Override
    public void save(UsageRecord record) {
        if (record == null) {
            return;
        }
        Instant at = record.recordedAt() != null ? record.recordedAt() : Instant.now();
        Path dayDir = usageDir.resolve(DAY.format(LocalDate.ofInstant(at, ZoneOffset.UTC)));
        try {
            Files.createDirectories(dayDir);
            Path file = dayDir.resolve(fileName(at));
            String content = YamlParser.serialize(new YamlDocument(toFrontmatter(record), ""));
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save usage record", e);
        }
    }

    /**
     * Reads a previously saved record back from disk (used by tests and future reporting).
     */
    public UsageRecord read(Path file) throws IOException {
        Map<String, String> fm = YamlParser.parse(Files.readString(file)).frontmatter();
        return fromFrontmatter(fm);
    }

    Path usageDirectory() {
        return usageDir;
    }

    private String fileName(Instant at) {
        String time = TIME.format(LocalTime.ofInstant(at, ZoneOffset.UTC));
        int n = sequence.incrementAndGet() % 10000;
        return "%s-%04d.yaml".formatted(time, n);
    }

    static Map<String, String> toFrontmatter(UsageRecord record) {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("recordedAt", record.recordedAt() != null ? record.recordedAt().toString() : Instant.now().toString());
        fm.put("promptTokens", Integer.toString(record.promptTokens()));
        fm.put("completionTokens", Integer.toString(record.completionTokens()));
        fm.put("totalTokens", Integer.toString(record.totalTokens()));
        fm.put("model", nullToEmpty(record.model()));
        fm.put("provider", nullToEmpty(record.provider()));
        fm.put("source", record.source() != null ? record.source().wireName() : UsageSource.UNKNOWN.wireName());
        fm.put("conversationId", nullToEmpty(record.conversationId()));
        if (record.taskId() != null && !record.taskId().isBlank()) {
            fm.put("taskId", record.taskId());
        }
        return fm;
    }

    static UsageRecord fromFrontmatter(Map<String, String> fm) {
        String recordedAt = fm.getOrDefault("recordedAt", Instant.EPOCH.toString());
        return new UsageRecord(
                Instant.parse(recordedAt),
                parseInt(fm.get("promptTokens")),
                parseInt(fm.get("completionTokens")),
                parseInt(fm.get("totalTokens")),
                fm.getOrDefault("model", ""),
                fm.getOrDefault("provider", ""),
                UsageSource.fromWireName(fm.get("source")),
                fm.getOrDefault("conversationId", ""),
                blankToNull(fm.get("taskId"))
        );
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value.trim());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
