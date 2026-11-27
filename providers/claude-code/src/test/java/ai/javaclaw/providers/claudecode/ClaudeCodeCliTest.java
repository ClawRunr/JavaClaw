package ai.javaclaw.providers.claudecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeCliTest {

    @Test
    void refusesInheritedOverridesThatWouldBillAnApiKeyOrAnotherBackend() {
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-secret", "CLAUDE_CODE_USE_BEDROCK", "1", "CLAUDE_CODE_USE_VERTEX", "0");

        assertThatThrownBy(() -> ClaudeCodeCli.childEnvironment(env, true))
                .hasMessageContaining("ANTHROPIC_API_KEY, CLAUDE_CODE_USE_BEDROCK")
                .hasMessageNotContaining("sk-secret")
                .hasMessageNotContaining("CLAUDE_CODE_USE_VERTEX");
    }

    @Test
    void anExplicitFixtureEnvironmentMayCarryOverrides() {
        Map<String, String> env = ClaudeCodeCli.childEnvironment(Map.of("ANTHROPIC_API_KEY", "fixture"), false);

        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "fixture");
    }

    @Test
    void preparesTheChildEnvironment() {
        Map<String, String> env = ClaudeCodeCli.childEnvironment(Map.of(
                "PATH", "/usr/bin",
                "CLAUDECODE", "1",
                "CLAUDE_CODE_ENTRYPOINT", "cli",
                ClaudeCodeCli.CONFIG_DIR_ENV, "~/claude-work"), true);

        assertThat(env).doesNotContainKeys("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", ClaudeCodeCli.CONFIG_DIR_ENV)
                .containsEntry("CLAUDE_CONFIG_DIR", System.getProperty("user.home") + "/claude-work")
                .containsEntry("DISABLE_AUTOUPDATER", "1")
                .containsEntry("CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "1")
                .containsKey("USER");
    }

    @Test
    void resolvesAnOverrideCommandOrTheUsualInstallDirectories(@TempDir Path home) throws IOException {
        Path local = Files.createDirectories(home.resolve(".local/bin"));
        Path claude = Files.createFile(local.resolve("claude"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        assertThat(ClaudeCodeCli.resolve(null, Map.of("PATH", "/nonexistent", "HOME", home.toString()))).contains(claude.toString());
        assertThat(ClaudeCodeCli.resolve(null, Map.of("PATH", "/nonexistent", "HOME", "/nonexistent",
                ClaudeCodeCli.COMMAND_ENV, claude.toString()))).contains(claude.toString());
        // An explicit override is the user's choice: it is never replaced by a guess.
        assertThat(ClaudeCodeCli.resolve(null, Map.of("PATH", "/nonexistent", "HOME", home.toString(),
                ClaudeCodeCli.COMMAND_ENV, "/nonexistent/claude"))).isEmpty();
    }
}
