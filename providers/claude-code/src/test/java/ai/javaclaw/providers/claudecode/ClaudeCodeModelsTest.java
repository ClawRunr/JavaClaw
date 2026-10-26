package ai.javaclaw.providers.claudecode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeModelsTest {

    @ParameterizedTest
    @CsvSource({
            "sonnet, claude-sonnet-5-5[1m]",
            "opus, claude-opus-5-5[1m]",
            "fable, claude-fable-5-1[1m]",
            "haiku, claude-haiku-5-5[1m]",
            "sonnet-5.5, claude-sonnet-5-5[1m]",
            "sonnet-5-5, claude-sonnet-5-5[1m]",
            "opus-4.8, claude-opus-4-8[1m]",
            "claude-opus-5[1m], claude-opus-5[1m]",
            "haiku-4-5, claude-haiku-4-5-20251001",
            "claude-haiku-4-5-20251001, claude-haiku-4-5-20251001",
            // Unknown models reach the CLI as typed and get its 200K default.
            "claude-sonnet-4-6, claude-sonnet-4-6",
            "claude-next-1[1m], claude-next-1[1m]"
    })
    void resolvesAliasesAndSelectsTheLongContextRouteExplicitly(String configured, String expected) {
        assertThat(ClaudeCodeModels.nativeModel(configured)).isEqualTo(expected);
    }

    @Test
    void refusesALongContextWindowAModelDoesNotHave() {
        assertThatThrownBy(() -> ClaudeCodeModels.nativeModel("claude-haiku-4-5[1m]")).isInstanceOf(IllegalArgumentException.class);
    }
}
