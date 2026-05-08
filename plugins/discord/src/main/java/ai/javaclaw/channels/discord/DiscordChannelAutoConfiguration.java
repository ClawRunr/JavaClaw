package ai.javaclaw.channels.discord;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.EnumSet;

@AutoConfiguration
public class DiscordChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.discord", name = {"token", "allowed-user"})
    public DiscordChannel discordChannel(@Value("${agent.channels.discord.allowed-user}") String allowedUser,
                                         Agent agent,
                                         ChannelRegistry channelRegistry) {
        return new DiscordChannel(allowedUser, agent, channelRegistry);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.channels.discord", name = {"token", "allowed-user"})
    public JDA discordJda(@Value("${agent.channels.discord.token}") String token,
                          DiscordChannel discordChannel) throws InterruptedException {
        return JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.DIRECT_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT)
                .disableCache(EnumSet.allOf(CacheFlag.class))
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .addEventListeners(discordChannel)
                .build()
                .awaitReady();
    }
}
