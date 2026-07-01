package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OllamaAgentAutoConfiguration {

    @Bean
    public AgentOnboardingProvider ollamaAgentOnboardingProvider() {
        return new OllamaAgentOnboardingProvider();
    }
}