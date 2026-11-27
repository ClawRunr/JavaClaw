package ai.javaclaw;

import ai.javaclaw.agent.ChatHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application, on a workspace and an application database of its own.
 */
@SpringBootTest
class JavaClawApplicationTests {

    private static final Path WORKSPACE = workspace();

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("agent.workspace", () -> WORKSPACE.toUri().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:javaclaw-application-test");
        registry.add("jobrunr.dashboard.enabled", () -> "false");
    }

    @Autowired
    ApplicationContext context;

    @Test
    void startsWithSessionsInTheirOwnDatabaseAndJobRunrOnTheApplicationDatabase() {
        assertThat(context.getBean(ChatHistory.class).conversationIds()).isEmpty();
        assertThat(WORKSPACE.resolve("data/sessions.mv.db")).isRegularFile();

        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        JdbcTemplate app = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(tables(app, "JOBRUNR_%")).isPositive();
        assertThat(tables(app, "AI_SESSION%")).isZero();
    }

    private static int tables(JdbcTemplate database, String namePattern) {
        return database.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) LIKE ?",
                Integer.class, namePattern);
    }

    private static Path workspace() {
        try {
            Path workspace = Files.createTempDirectory("javaclaw-workspace");
            Files.writeString(workspace.resolve("AGENT.md"), "You are a test assistant.");
            Files.writeString(workspace.resolve("INFO.md"), "");
            // The skills tool refuses to start without at least one skill
            Path skill = Files.createDirectories(workspace.resolve("skills/greeting"));
            Files.writeString(skill.resolve("SKILL.md"), """
                    ---
                    name: greeting
                    description: Greets the user.
                    ---
                    Say hello.
                    """);
            return workspace;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
