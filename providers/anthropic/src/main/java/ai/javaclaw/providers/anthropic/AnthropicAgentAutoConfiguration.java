package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AnthropicAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AnthropicAgentOnboardingProvider.class)
    public AgentOnboardingProvider anthropicAgentOnboardingProvider() {
        return new AnthropicAgentOnboardingProvider();
    }
}