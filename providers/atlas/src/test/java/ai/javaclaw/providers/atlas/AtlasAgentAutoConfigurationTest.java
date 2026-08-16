package ai.javaclaw.providers.atlas;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static ai.javaclaw.testsupport.AutoConfigurationImportsTestSupport.importedAutoConfigurations;
import static org.assertj.core.api.Assertions.assertThat;

class AtlasAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtlasAgentAutoConfiguration.class));

    @Test
    void registersAtlasOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            AgentOnboardingProvider provider = context.getBean(AgentOnboardingProvider.class);
            assertThat(provider.getId()).isEqualTo("atlas");
            assertThat(provider.chatModelId()).isEqualTo("openai");
            assertThat(provider.defaultModel()).isEqualTo("qwen/qwen3.8-max");
            assertThat(provider.createPropertyKey("api-key")).isEqualTo("spring.ai.openai.api-key");
            assertThat(provider.additionalProperties())
                    .containsEntry("spring.ai.openai.base-url", "https://api.atlascloud.ai");
        });
    }

    @Test
    void autoConfigurationIsRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations(AtlasAgentAutoConfigurationTest.class))
                .contains(AtlasAgentAutoConfiguration.class.getName());
    }
}
