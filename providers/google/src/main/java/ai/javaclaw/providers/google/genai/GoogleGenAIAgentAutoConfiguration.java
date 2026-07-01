package ai.javaclaw.providers.google.genai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class GoogleGenAIAgentAutoConfiguration {

    @Bean
    public AgentOnboardingProvider googleGenAIAgentOnboardingProvider() {
        return new GoogleGenAIAgentOnboardingProvider();
    }
}