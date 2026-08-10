package ai.javaclaw.providers.atlas;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AtlasAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AtlasAgentOnboardingProvider.class)
    public AgentOnboardingProvider atlasAgentOnboardingProvider() {
        return new AtlasAgentOnboardingProvider();
    }
}
