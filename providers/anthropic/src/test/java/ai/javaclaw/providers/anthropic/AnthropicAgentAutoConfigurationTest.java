package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AnthropicAgentAutoConfiguration.class));

    @Test
    void registersOnboardingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentOnboardingProvider.class);
            assertThat(context.getBean(AgentOnboardingProvider.class).getId()).isEqualTo("anthropic");
        });
    }

    @Test
    void bothAutoConfigurationsAreRegisteredViaImportsFile() throws IOException {
        assertThat(importedAutoConfigurations()).contains(
                AnthropicAgentAutoConfiguration.class.getName(),
                AnthropticClaudeCodeConfiguration.class.getName());
    }

    static List<String> importedAutoConfigurations() throws IOException {
        List<String> classNames = new ArrayList<>();
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .forEach(classNames::add);
            }
        }
        return classNames;
    }
}