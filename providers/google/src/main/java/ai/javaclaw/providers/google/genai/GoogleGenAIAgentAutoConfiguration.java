package ai.javaclaw.providers.google.genai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class GoogleGenAIAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GoogleGenAIAgentOnboardingProvider.class)
    public AgentOnboardingProvider googleGenaiAgentOnboardingProvider(ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new GoogleGenAIAgentOnboardingProvider(restClientBuilder.getIfAvailable(RestClient::builder));
    }
}
