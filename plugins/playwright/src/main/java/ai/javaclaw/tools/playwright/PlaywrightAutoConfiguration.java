package ai.javaclaw.tools.playwright;

import ai.javaclaw.approval.ToolRisk;
import ai.javaclaw.approval.ToolRiskContributor;
import ai.javaclaw.tools.AutoDiscoveredTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.playwright.enabled", havingValue = "true")
public class PlaywrightAutoConfiguration {

    @Bean
    public AutoDiscoveredTool<PlaywrightBrowserTool> autoDiscoveredPlaywrightBrowserTool(PlaywrightBrowserTool playwrightBrowserTool) {
        return new AutoDiscoveredTool<>(playwrightBrowserTool);
    }

    /**
     * Driving the browser reaches the network as the user; reading the current page does not.
     * {@code takeScreenshot} only writes a PNG to the temp directory; {@code evaluateJavaScript}
     * runs arbitrary code in the page.
     */
    @Bean
    public ToolRiskContributor playwrightToolRisks() {
        return () -> Map.of(
                "navigateTo", ToolRisk.NETWORK,
                "clickElement", ToolRisk.NETWORK,
                "fillInput", ToolRisk.NETWORK,
                "evaluateJavaScript", ToolRisk.EXECUTE,
                "getText", ToolRisk.READ_ONLY,
                "takeScreenshot", ToolRisk.READ_ONLY,
                "waitForSelector", ToolRisk.READ_ONLY,
                "closeBrowser", ToolRisk.READ_ONLY);
    }

    @Bean(destroyMethod = "close")
    public PlaywrightBrowserTool playwrightBrowserTool(@Value("${agent.tools.playwright.headless:true}") boolean headless) {
        return PlaywrightBrowserTool.builder().headless(headless).build();
    }
}
