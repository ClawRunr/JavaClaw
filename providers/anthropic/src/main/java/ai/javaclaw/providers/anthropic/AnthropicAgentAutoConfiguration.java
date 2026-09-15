package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class AnthropicAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AnthropicAgentOnboardingProvider.class)
    public AgentOnboardingProvider anthropicAgentOnboardingProvider(ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new AnthropicAgentOnboardingProvider(restClientBuilder.getIfAvailable(RestClient::builder));
    }
}
