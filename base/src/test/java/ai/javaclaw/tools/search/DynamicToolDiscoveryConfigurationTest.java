package ai.javaclaw.tools.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolDiscoveryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamicToolDiscoveryConfiguration.class);

    @Test
    void whenPropertyIsMissing_defaultsToEnabled() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolIndex.class);
                    assertThat(context).hasSingleBean(ToolSearchToolCallingAdvisor.class);
                    assertThat(context.getBean(DynamicToolDiscoveryProperties.class).enabled()).isTrue();
                });
    }

    @Test
    void whenEnabled_registersToolIndexAndAdvisor() {
        contextRunner
                .withPropertyValues(
                        "javaclaw.tools.dynamic-discovery.enabled=true",
                        "javaclaw.tools.dynamic-discovery.max-results=7",
                        "javaclaw.tools.dynamic-discovery.lucene-min-score-threshold=0.0"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolIndex.class);
                    assertThat(context).hasSingleBean(ToolSearchToolCallingAdvisor.class);
                    assertThat(context.getBean(DynamicToolDiscoveryProperties.class).enabled()).isTrue();
                });
    }

    @Test
    void whenDisabled_doesNotRegisterToolIndexOrAdvisor() {
        contextRunner
                .withPropertyValues("javaclaw.tools.dynamic-discovery.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ToolIndex.class);
                    assertThat(context).doesNotHaveBean(ToolSearchToolCallingAdvisor.class);
                });
    }
}
