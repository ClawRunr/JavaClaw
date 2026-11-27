package ai.javaclaw.providers.claudecode;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ClaudeCodeAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClaudeCodeAgentOnboardingProvider.class)
    public AgentOnboardingProvider claudeCodeAgentOnboardingProvider() {
        return new ClaudeCodeAgentOnboardingProvider();
    }
}
