package ai.javaclaw;

import ai.javaclaw.approval.ApprovalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The approval defaults ship in {@code application.yaml}, not in Java. */
class ApprovalDefaultsTest {

    @Test
    void applicationYamlCarriesTheDefaultAllowListAndProtectedPaths() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);

        ApprovalProperties properties = Binder.get(environment)
                .bindOrCreate("agent.approval", ApprovalProperties.class);

        assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.allow()).containsExactly("Read", "Skill", "toolSearchTool", "CheckListTool",
                "listRecurringTasks", "getText", "waitForSelector", "closeBrowser", "takeScreenshot", "Glob", "Grep",
                "ListDirectory", "BashOutput", "TodoWrite", "MemoryView");
        assertThat(properties.deny()).isEmpty();
        assertThat(properties.protectedPaths()).containsExactly("application*.yaml", "application*.yml",
                "application*.properties", "workspace/conversations/", "workspace/AGENT.md", "workspace/INFO.md",
                "workspace/skills/", "workspace/agents/");
    }
}
