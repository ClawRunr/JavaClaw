package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ChannelRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ai.javaclaw.agent.Agent;

import java.net.http.HttpClient;

@AutoConfiguration
@EnableConfigurationProperties(TeamsProperties.class)
public class TeamsChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BotFrameworkTokenProvider botFrameworkTokenProvider(TeamsProperties properties) {
        return new BotFrameworkTokenProvider(properties, HttpClient.newHttpClient());
    }

    @Bean
    @ConditionalOnMissingBean
    public BotFrameworkJwtValidator botFrameworkJwtValidator(TeamsProperties properties) {
        return new BotFrameworkJwtValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.teams", name = "enabled", havingValue = "true")
    public TeamsChannel teamsChannel(BotFrameworkTokenProvider tokenProvider, ChannelRegistry channelRegistry) {
        return new TeamsChannel(tokenProvider, channelRegistry);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agent.channels.teams",
            name = "enabled",
            havingValue = "true"
    )
    public TeamsWebhookController teamsWebhookController(
            TeamsProperties properties,
            ChannelRegistry channelRegistry,
            Agent agent,
            TeamsChannel channel,
            BotFrameworkJwtValidator jwtValidator) {

        return new TeamsWebhookController(
                properties,
                channelRegistry,
                agent,
                channel,
                jwtValidator
        );
    }
}