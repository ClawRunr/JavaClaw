package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link DefaultAgent} through a real {@link ChatClient} and {@link ToolCallingAdvisor}, with
 * only the model faked. {@link ToolCallObservingAdvisor} sees tool calls only from inside that
 * loop, so its unit test cannot prove the events actually come out — this does.
 */
class DefaultAgentToolLoopTest {

    static final String WEATHER_ARGS = "{\"city\":\"Paris\"}";

    final List<AgentEvent> events = new ArrayList<>();

    @Test
    void reportsToolCallAndResultBetweenTheTokensOfAStreamedAnswer() {
        agentFor(new WeatherModel(true)).respondTo("web", "Weather in Paris?", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Let me check. "),
                new AgentEvent.ToolCall("call-1", "weather", WEATHER_ARGS),
                new AgentEvent.ToolResult("call-1", "weather", "\"sunny\""),
                new AgentEvent.Token("It is "),
                new AgentEvent.Token("sunny."),
                new AgentEvent.Done());
    }

    @Test
    void reportsToolCallAndResultWhenTheModelCannotStream() {
        agentFor(new WeatherModel(false)).respondTo("web", "Weather in Paris?", events::add);

        assertThat(events).containsExactly(
                new AgentEvent.ToolCall("call-1", "weather", WEATHER_ARGS),
                new AgentEvent.ToolResult("call-1", "weather", "\"sunny\""),
                new AgentEvent.Token("It is sunny."),
                new AgentEvent.Done());
    }

    private static DefaultAgent agentFor(ChatModel model) {
        return new DefaultAgent(ChatClient.builder(model)
                .defaultTools(new WeatherTools())
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .build());
    }

    static class WeatherTools {
        @Tool(description = "Current weather for a city")
        String weather(String city) {
            return "sunny";
        }
    }

    /** Asks for the weather tool first, then answers once the tool's response is in the prompt. */
    record WeatherModel(boolean streams) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return answered(prompt) ? text("It is sunny.") : toolCall("");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (!streams) {
                return ChatModel.super.stream(prompt);
            }
            return answered(prompt)
                    ? Flux.just(text("It is "), text("sunny."))
                    : Flux.just(text("Let me check. "), toolCall(""));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static boolean answered(Prompt prompt) {
            return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
        }

        private static ChatResponse text(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        private static ChatResponse toolCall(String text) {
            AssistantMessage message = AssistantMessage.builder()
                    .content(text)
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "weather", WEATHER_ARGS)))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }
}
