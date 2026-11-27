package ai.javaclaw.providers.claudecode;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClaudeCodeAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            AgentOnboardingProvider provider = context.getBean(AgentOnboardingProvider.class);
            assertThat(provider.getId()).isEqualTo("claude-code");
            assertThat(provider.requiresApiKey()).isFalse();
            assertThat(provider.supportsBaseUrl()).isFalse();
        });
    }

    @Test
    void autoConfigurationIsRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(ClaudeCodeAgentAutoConfigurationTest.class))
                .contains(ClaudeCodeAgentAutoConfiguration.class.getName());
    }
}
