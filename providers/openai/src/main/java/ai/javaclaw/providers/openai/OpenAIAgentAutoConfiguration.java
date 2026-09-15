package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class OpenAIAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenAIAgentOnboardingProvider.class)
    public AgentOnboardingProvider openAIAgentOnboardingProvider(ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new OpenAIAgentOnboardingProvider(restClientBuilder.getIfAvailable(RestClient::builder));
    }
}
