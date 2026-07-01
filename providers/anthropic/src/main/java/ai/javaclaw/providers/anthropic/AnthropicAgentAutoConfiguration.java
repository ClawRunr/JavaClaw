package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AnthropicAgentAutoConfiguration {

    @Bean
    public AgentOnboardingProvider anthropicAgentOnboardingProvider() {
        return new AnthropicAgentOnboardingProvider();
    }
}