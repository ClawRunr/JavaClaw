package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AnthropicAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            assertThat(context.getBean(AgentOnboardingProvider.class).getId()).isEqualTo("anthropic");
        });
    }

    @Test
    void bothAutoConfigurationsAreRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(AnthropicAgentAutoConfigurationTest.class)).contains(
                AnthropicAgentAutoConfiguration.class.getName(),
                AnthropticClaudeCodeConfiguration.class.getName());
    }
}