package ai.javaclaw.tools.playwright;

import ai.javaclaw.tools.AutoDiscoveredTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.playwright.enabled", havingValue = "true")
public class PlaywrightAutoConfiguration {

    @Bean
    public AutoDiscoveredTool<PlaywrightBrowserTool> autoDiscoveredPlaywrightBrowserTool(PlaywrightBrowserTool playwrightBrowserTool) {
        return new AutoDiscoveredTool<>(playwrightBrowserTool);
    }

    @Bean(destroyMethod = "close")
    public PlaywrightBrowserTool playwrightBrowserTool(@Value("${agent.tools.playwright.headless:true}") boolean headless) {
        return PlaywrightBrowserTool.builder().headless(headless).build();
    }
}
