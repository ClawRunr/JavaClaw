package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.ChannelRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(WacliProperties.class)
public class WhatsAppChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.whatsapp", name = "enabled", havingValue = "true")
    public WacliWhatsAppChannel wacliWhatsAppChannel(WacliProperties properties, ChannelRegistry channelRegistry,
                                                     @Value("${server.port:8080}") int webhookPort) {
        return new WacliWhatsAppChannel(properties, channelRegistry, webhookPort);
    }
}
