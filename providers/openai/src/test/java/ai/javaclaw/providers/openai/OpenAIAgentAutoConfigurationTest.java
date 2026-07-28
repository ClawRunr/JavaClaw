package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAIAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAIAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            assertThat(context.getBean(AgentOnboardingProvider.class).getId()).isEqualTo("openai");
        });
    }

    @Test
    void autoConfigurationIsRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(OpenAIAgentAutoConfigurationTest.class))
                .contains(OpenAIAgentAutoConfiguration.class.getName());
    }
}