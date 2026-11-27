package ai.javaclaw.providers.google.genai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleGenAIAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GoogleGenAIAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            assertThat(context.getBean(AgentOnboardingProvider.class).getId()).isEqualTo("google.genai");
        });
    }

    @Test
    void autoConfigurationIsRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(GoogleGenAIAgentAutoConfigurationTest.class))
                .contains(GoogleGenAIAgentAutoConfiguration.class.getName());
    }
}