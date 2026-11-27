package ai.javaclaw.llm;

import ai.javaclaw.llm.SubagentStore.Subagent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentStoreTest {

    @TempDir
    Path workspaceDir;

    SubagentStore store;

    @BeforeEach
    void setUp() {
        store = new SubagentStore(new FileSystemResource(workspaceDir));
    }

    /** The {@code agents} directory inside the temporary workspace. */
    private Path agentsDir() {
        return workspaceDir.resolve("agents");
    }

    /** Writes a raw agent file, creating {@code workspace/agents} on the way. */
    private Path writeAgentFile(String fileName, String content) throws IOException {
        Files.createDirectories(agentsDir());
        return Files.writeString(agentsDir().resolve(fileName), content, StandardCharsets.UTF_8);
    }

    @Test
    void agentsDirectoryResolvesUnderWorkspace() {
        assertThat(store.agentsDirectory()).isEqualTo(workspaceDir.resolve("agents"));
    }

    @Test
    void listReturnsEmptyWhenDirectoryIsMissing() {
        assertThat(store.agentsDirectory()).doesNotExist();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void listReadsFrontmatterAndBodySortedByFileName() throws IOException {
        writeAgentFile("reviewer.md", """
                ---
                name: reviewer
                description: Reviews code
                model: anthropic:claude-opus-4
                ---
                You review code carefully.
                """);
        writeAgentFile("planner.md", """
                ---
                name: planner
                description: Plans work
                model: openai
                ---
                You plan work.
                """);

        List<Subagent> agents = store.list();

        assertThat(agents).extracting(Subagent::name).containsExactly("planner", "reviewer");
        Subagent planner = agents.getFirst();
        assertThat(planner.description()).isEqualTo("Plans work");
        assertThat(planner.model()).isEqualTo("openai");
        assertThat(planner.content()).isEqualTo("You plan work.\n");
        assertThat(agents.get(1).model()).isEqualTo("anthropic:claude-opus-4");
    }

    @Test
    void listIgnoresNonMarkdownFiles() throws IOException {
        writeAgentFile("reviewer.md", "---\nname: reviewer\n---\nBody");
        writeAgentFile("notes.txt", "not an agent");
        writeAgentFile("config.yaml", "not an agent either");

        assertThat(store.list()).extracting(Subagent::name).containsExactly("reviewer");
    }

    @Test
    void listSkipsEntriesThatCannotBeRead() throws IOException {
        writeAgentFile("reviewer.md", "---\nname: reviewer\n---\nBody");
        // A directory whose name ends in .md passes the filter but cannot be read as a file.
        Files.createDirectory(agentsDir().resolve("broken.md"));

        assertThat(store.list()).extracting(Subagent::name).containsExactly("reviewer");
    }

    @Test
    void getFallsBackToFileNameAndEmptyFieldsWhenFrontmatterIsIncomplete() throws IOException {
        writeAgentFile("nameless.md", "Just instructions, no frontmatter.");

        Subagent agent = store.get("nameless").orElseThrow();

        assertThat(agent.name()).isEqualTo("nameless");
        assertThat(agent.model()).isEmpty();
        assertThat(agent.description()).isEmpty();
        assertThat(agent.content()).isEqualTo("Just instructions, no frontmatter.");
    }

    @Test
    void getPrefersFrontmatterNameOverFileName() throws IOException {
        writeAgentFile("file-name.md", "---\nname: declared-name\n---\nBody");

        assertThat(store.get("file-name").orElseThrow().name()).isEqualTo("declared-name");
    }

    @Test
    void getReturnsEmptyForUnknownAgent() {
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void getReturnsEmptyWhenPathIsADirectory() throws IOException {
        Files.createDirectories(agentsDir().resolve("reviewer.md"));

        assertThat(store.get("reviewer")).isEmpty();
    }

    @Test
    void existsReflectsPresenceOfTheFile() throws IOException {
        writeAgentFile("reviewer.md", "---\nname: reviewer\n---\nBody");

        assertThat(store.exists("reviewer")).isTrue();
        assertThat(store.exists("missing")).isFalse();
    }

    @Test
    void saveCreatesTheDirectoryAndWritesFrontmatterInOrder() throws IOException {
        store.save(new Subagent("reviewer", "anthropic:claude-opus-4", "Reviews code", "You review code."));

        Path file = agentsDir().resolve("reviewer.md");
        assertThat(Files.readString(file)).isEqualToNormalizingNewlines("""
                ---
                name: reviewer
                description: Reviews code
                model: anthropic:claude-opus-4
                ---
                You review code.""");
    }

    @Test
    void saveStripsDescriptionAndModel() throws IOException {
        store.save(new Subagent("reviewer", "  openai  ", "  Reviews code  ", "Body"));

        Subagent reloaded = store.get("reviewer").orElseThrow();
        assertThat(reloaded.model()).isEqualTo("openai");
        assertThat(reloaded.description()).isEqualTo("Reviews code");
    }

    @Test
    void saveOmitsBlankDescriptionAndModel() throws IOException {
        store.save(new Subagent("reviewer", "   ", null, "Body"));

        String raw = Files.readString(agentsDir().resolve("reviewer.md"));
        assertThat(raw).doesNotContain("description:").doesNotContain("model:");
        Subagent reloaded = store.get("reviewer").orElseThrow();
        assertThat(reloaded.model()).isEmpty();
        assertThat(reloaded.description()).isEmpty();
    }

    @Test
    void saveTreatsNullContentAsEmptyBody() throws IOException {
        store.save(new Subagent("reviewer", "openai", "Reviews code", null));

        Subagent reloaded = store.get("reviewer").orElseThrow();
        assertThat(reloaded.content()).isEmpty();
        assertThat(reloaded.name()).isEqualTo("reviewer");
        assertThat(reloaded.model()).isEqualTo("openai");
    }

    @Test
    void saveOverwritesAnExistingAgent() throws IOException {
        store.save(new Subagent("reviewer", "openai", "First", "First body."));
        store.save(new Subagent("reviewer", "anthropic", "Second", "Second body."));

        assertThat(store.list()).hasSize(1);
        Subagent reloaded = store.get("reviewer").orElseThrow();
        assertThat(reloaded.description()).isEqualTo("Second");
        assertThat(reloaded.model()).isEqualTo("anthropic");
        assertThat(reloaded.content()).isEqualTo("Second body.");
    }

    @Test
    void savedAgentRoundTripsThroughGet() throws IOException {
        Subagent agent = new Subagent("reviewer", "anthropic:claude-opus-4", "Reviews code",
                "Line one\n\nLine two\n");

        store.save(agent);

        assertThat(store.get("reviewer")).contains(agent);
    }

    @Test
    void deleteRemovesTheFileAndReportsWhetherSomethingWasRemoved() throws IOException {
        store.save(new Subagent("reviewer", "openai", "Reviews code", "Body"));

        assertThat(store.delete("reviewer")).isTrue();
        assertThat(store.exists("reviewer")).isFalse();
        assertThat(store.delete("reviewer")).isFalse();
    }

    @Test
    void deleteReturnsFalseForUnknownAgent() throws IOException {
        assertThat(store.delete("missing")).isFalse();
    }

    @Test
    void nonFileWorkspaceDisablesStorage() throws IOException {
        SubagentStore inMemory = new SubagentStore(new ByteArrayResource(new byte[0]));

        assertThat(inMemory.agentsDirectory()).isNull();
        assertThat(inMemory.list()).isEmpty();
        assertThat(inMemory.get("reviewer")).isEqualTo(Optional.empty());
        assertThat(inMemory.exists("reviewer")).isFalse();
        assertThat(inMemory.delete("reviewer")).isFalse();
        assertThatThrownBy(() -> inMemory.save(new Subagent("reviewer", "openai", "d", "Body")))
                .isInstanceOf(IOException.class)
                .hasMessage("Agents directory is not available");
    }
}
