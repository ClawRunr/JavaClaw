package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class OllamaAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OllamaAgentOnboardingProvider.class)
    public AgentOnboardingProvider ollamaAgentOnboardingProvider(ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new OllamaAgentOnboardingProvider(restClientBuilder.getIfAvailable(RestClient::builder));
    }
}
