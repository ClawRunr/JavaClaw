package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OpenAIAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenAIAgentOnboardingProvider.class)
    public AgentOnboardingProvider openAIAgentOnboardingProvider() {
        return new OpenAIAgentOnboardingProvider();
    }
}