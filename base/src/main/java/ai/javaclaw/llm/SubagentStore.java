package ai.javaclaw.llm;

import ai.javaclaw.files.YamlDocument;
import ai.javaclaw.files.YamlParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads and writes subagent definition files under {@code workspace/agents/}. Each subagent is one
 * Markdown file with {@code name}, {@code description} and {@code model} frontmatter and a free-form
 * body (the subagent's instructions).
 */
@Component
public class SubagentStore {

    private final SubagentReferenceScanner scanner;

    public SubagentStore(SubagentReferenceScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * @param name        the subagent id (also its file name without {@code .md})
     * @param model       the provider name this subagent runs on
     * @param description short summary of what the subagent does
     * @param content     the subagent's instructions (Markdown body)
     */
    public record Subagent(String name, String model, String description, String content) {
    }

    public List<Subagent> list() {
        Path dir = scanner.agentsDirectory();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Subagent> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> read(p).ifPresent(result::add));
        } catch (IOException e) {
            throw new RuntimeException("Failed to list subagents", e);
        }
        return result;
    }

    public Optional<Subagent> get(String name) {
        Path file = fileFor(name);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return read(file);
    }

    public boolean exists(String name) {
        Path file = fileFor(name);
        return file != null && Files.isRegularFile(file);
    }

    public void save(Subagent subagent) throws IOException {
        Path dir = scanner.agentsDirectory();
        if (dir == null) {
            throw new IOException("Subagents directory is not available");
        }
        Files.createDirectories(dir);

        Map<String, String> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", subagent.name());
        if (subagent.description() != null && !subagent.description().isBlank()) {
            frontmatter.put("description", subagent.description().strip());
        }
        if (subagent.model() != null && !subagent.model().isBlank()) {
            frontmatter.put("model", subagent.model().strip());
        }
        String body = subagent.content() == null ? "" : subagent.content();
        String serialized = YamlParser.serialize(new YamlDocument(frontmatter, body));
        Files.writeString(dir.resolve(subagent.name() + ".md"), serialized, StandardCharsets.UTF_8);
    }

    public boolean delete(String name) throws IOException {
        Path file = fileFor(name);
        if (file == null) {
            return false;
        }
        return Files.deleteIfExists(file);
    }

    private Optional<Subagent> read(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            YamlDocument doc = YamlParser.parse(raw);
            String fileName = file.getFileName().toString();
            String defaultName = fileName.substring(0, fileName.length() - ".md".length());
            String name = doc.frontmatter().getOrDefault("name", defaultName);
            String model = doc.frontmatter().getOrDefault("model", "");
            String description = doc.frontmatter().getOrDefault("description", "");
            return Optional.of(new Subagent(name, model, description, doc.body()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path fileFor(String name) {
        Path dir = scanner.agentsDirectory();
        return dir == null ? null : dir.resolve(name + ".md");
    }
}
