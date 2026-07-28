package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class OllamaAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OllamaAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            assertThat(context.getBean(AgentOnboardingProvider.class).getId()).isEqualTo("ollama");
        });
    }

    @Test
    void autoConfigurationIsRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(OllamaAgentAutoConfigurationTest.class))
                .contains(OllamaAgentAutoConfiguration.class.getName());
    }
}