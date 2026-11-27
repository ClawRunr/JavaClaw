package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.cli.CliRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppChannelAutoConfiguration {

    /**
     * Always available, because onboarding uses it to check the wacli setup. It does nothing until
     * {@link WhatsApp#start()} is called, which only {@link WhatsAppChannel} does.
     */
    @Bean
    @ConditionalOnMissingBean
    public WhatsApp whatsApp(WhatsAppProperties properties, CliRunner cliRunner,
                             @Value("${server.port:8080}") int webhookPort) {
        String webhookUrl = "http://localhost:" + webhookPort + WhatsAppWebhookController.PATH;
        return new WhatsApp(properties.getAllowedChatJid(), cliRunner, webhookUrl);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.whatsapp", name = "enabled", havingValue = "true")
    public WhatsAppChannel whatsAppChannel(WhatsApp whatsApp, ChannelRegistry channelRegistry, Agent agent) {
        return new WhatsAppChannel(whatsApp, channelRegistry, agent);
    }
}
