package ai.javaclaw.llm;

import ai.javaclaw.files.YamlDocument;
import ai.javaclaw.files.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads the subagent definition files under {@code workspace/agents/} and exposes which provider
 * name each one references via its {@code model:} frontmatter field.
 */
@Component
public class SubagentReferenceScanner {

    private static final Logger log = LoggerFactory.getLogger(SubagentReferenceScanner.class);
    public static final String AGENTS_SUBDIRECTORY = "agents";

    private final Resource workspace;

    public SubagentReferenceScanner(@Value("${agent.workspace:file:./workspace/}") Resource workspace) {
        this.workspace = workspace;
    }

    /**
     * @param name  the subagent name (its frontmatter {@code name}, or the file name without suffix)
     * @param model the referenced provider name, or {@code null} if the subagent pins no model
     */
    public record SubagentReference(String name, String model) {
    }

    /** All subagent definitions found under {@code workspace/agents/}. Never throws. */
    public List<SubagentReference> scan() {
        Path agentsDir = agentsDirectory();
        if (agentsDir == null || !Files.isDirectory(agentsDir)) {
            return List.of();
        }
        List<SubagentReference> references = new ArrayList<>();
        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> toReference(p).ifPresent(references::add));
        } catch (IOException e) {
            log.warn("Failed to list subagent directory {}: {}", agentsDir, e.getMessage());
        }
        return references;
    }

    /** The set of subagent names that reference the given provider name. */
    public Set<String> namesReferencing(String providerName) {
        Set<String> names = new LinkedHashSet<>();
        for (SubagentReference reference : scan()) {
            if (providerName != null && providerName.equals(reference.model())) {
                names.add(reference.name());
            }
        }
        return names;
    }

    private Optional<SubagentReference> toReference(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            YamlDocument doc = YamlParser.parse(content);
            String fileName = file.getFileName().toString();
            String defaultName = fileName.substring(0, fileName.length() - ".md".length());
            String name = doc.frontmatter().getOrDefault("name", defaultName);
            // The "model" frontmatter is "<provider>" or "<provider>:<modelId>"; the referenced
            // provider is the part before the optional ':'.
            String model = doc.frontmatter().get("model");
            if (model != null) {
                int colon = model.indexOf(':');
                if (colon >= 0) {
                    model = model.substring(0, colon);
                }
                if (model.isBlank()) {
                    model = null;
                }
            }
            return Optional.of(new SubagentReference(name, model));
        } catch (IOException e) {
            log.warn("Failed to read subagent file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** The {@code workspace/agents} directory path, or {@code null} if it cannot be resolved. */
    public Path agentsDirectory() {
        try {
            return workspace.getFile().toPath().resolve(AGENTS_SUBDIRECTORY);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to resolve workspace directory", e);
        } catch (RuntimeException e) {
            log.debug("Workspace resource is not file-based; subagent scanning disabled");
            return null;
        }
    }
}
