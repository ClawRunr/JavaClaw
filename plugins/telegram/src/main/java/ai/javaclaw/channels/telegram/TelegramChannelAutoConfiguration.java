package ai.javaclaw.channels.telegram;


import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.speech.SpeechToTextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class TelegramChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.telegram", name = {"token", "username"})
    public TelegramChannel telegramChannel(@Value("${agent.channels.telegram.token:null}") String botToken,
                                           @Value("${agent.channels.telegram.username:null}") String allowedUsername,
                                           Agent agent,
                                           ChannelRegistry channelRegistry,
                                           SpeechToTextService speechToTextService) {
        return new TelegramChannel(botToken, allowedUsername, agent, channelRegistry, speechToTextService);
    }
}
