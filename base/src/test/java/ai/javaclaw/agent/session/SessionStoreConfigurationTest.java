package ai.javaclaw.agent.session;

import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.DefaultAgent;
import ai.javaclaw.agent.SessionChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session store as the application wires it: Spring Boot's own DataSource next to it, and
 * Spring AI's session service auto-configuration on top. A second context on the same workspace is
 * an application restart.
 */
class SessionStoreConfigurationTest {

    @TempDir
    Path workspace;

    private final String appDatabase = "jdbc:h2:mem:app-" + UUID.randomUUID();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        SessionServiceAutoConfiguration.class))
                .withUserConfiguration(SessionStoreConfiguration.class)
                .withPropertyValues("agent.workspace=" + workspace.toUri(), "spring.datasource.url=" + appDatabase);
    }

    @Test
    void aConversationWithItsToolCallsAndResultsSurvivesARestartInOrder() {
        runner().run(context -> agent(context).respondTo(AgentRequest.chat("web", SessionChannel.WEB, "Weather in Paris?")));

        runner().run(context -> {
            List<Message> log = context.getBean(SessionService.class).getMessages("web");

            assertThat(log).extracting(Message::getMessageType).containsExactly(
                    MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
            AssistantMessage.ToolCall call = ((AssistantMessage) log.get(1)).getToolCalls().getFirst();
            ToolResponseMessage.ToolResponse result = ((ToolResponseMessage) log.get(2)).getResponses().getFirst();
            assertThat(call.name()).isEqualTo("weather");
            assertThat(result.id()).isEqualTo(call.id());
            assertThat(result.responseData()).isEqualTo("\"sunny\"");
            assertThat(log.get(3).getText()).isEqualTo("It is sunny.");

            ChatHistory history = new ChatHistory(context.getBean(SessionService.class));
            assertThat(history.conversationIds()).containsExactly("web");
            assertThat(history.messages("web")).extracting(Message::getText)
                    .containsExactly("Weather in Paris?", "It is sunny.");
        });
    }

    @Test
    void theSessionsDatabaseIsItsOwnAndNotADataSourceBean() {
        runner().run(context -> {
            agent(context).respondTo(AgentRequest.chat("web", SessionChannel.WEB, "Weather in Paris?"));

            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            JdbcTemplate app = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(app.queryForObject("SELECT DATABASE_PATH()", String.class)).isNull(); // the in-memory app database
            assertThat(app.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'AI_SESSION%'", Integer.class))
                    .isZero();
            assertThat(workspace.resolve("data/sessions.mv.db")).isRegularFile();
        });
    }


    /** The agent wired as the application wires it: tool loop first, session advisors around and inside it. */
    private static DefaultAgent agent(ApplicationContext context) {
        return new DefaultAgent(ChatClient.builder(new WeatherModel())
                .defaultTools(new WeatherTools())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .defaultAdvisors(SessionAdvisors.builder(context.getBean(SessionService.class)).build())
                .build());
    }

    static class WeatherTools {
        @Tool(description = "Current weather for a city")
        String weather(String city) {
            return "sunny";
        }
    }

    /** Asks for the weather tool, then answers once the tool's result is in the prompt. */
    static class WeatherModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean answered = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            AssistantMessage reply = answered
                    ? new AssistantMessage("It is sunny.")
                    : AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "weather",
                                    "{\"city\":\"Paris\"}")))
                            .build();
            return new ChatResponse(List.of(new Generation(reply)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
