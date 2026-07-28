package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OllamaAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OllamaAgentOnboardingProvider.class)
    public AgentOnboardingProvider ollamaAgentOnboardingProvider() {
        return new OllamaAgentOnboardingProvider();
    }
}