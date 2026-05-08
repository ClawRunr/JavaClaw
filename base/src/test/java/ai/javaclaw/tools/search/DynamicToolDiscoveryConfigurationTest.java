package ai.javaclaw.tools.search;

import org.junit.jupiter.api.Test;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolDiscoveryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamicToolDiscoveryConfiguration.class);

    @Test
    void whenPropertyIsMissing_defaultsToEnabled() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolSearcher.class);
                    assertThat(context).hasSingleBean(ToolSearchToolCallAdvisor.class);
                    assertThat(context.getBean(DynamicToolDiscoveryProperties.class).enabled()).isTrue();
                });
    }

    @Test
    void whenEnabled_registersToolSearcherAndAdvisor() {
        contextRunner
                .withPropertyValues(
                        "javaclaw.tools.dynamic-discovery.enabled=true",
                        "javaclaw.tools.dynamic-discovery.max-results=7",
                        "javaclaw.tools.dynamic-discovery.lucene-min-score-threshold=0.0"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolSearcher.class);
                    assertThat(context).hasSingleBean(ToolSearchToolCallAdvisor.class);
                    assertThat(context.getBean(DynamicToolDiscoveryProperties.class).enabled()).isTrue();
                });
    }

    @Test
    void whenDisabled_doesNotRegisterToolSearcherOrAdvisor() {
        contextRunner
                .withPropertyValues("javaclaw.tools.dynamic-discovery.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ToolSearcher.class);
                    assertThat(context).doesNotHaveBean(ToolSearchToolCallAdvisor.class);
                });
    }
}
