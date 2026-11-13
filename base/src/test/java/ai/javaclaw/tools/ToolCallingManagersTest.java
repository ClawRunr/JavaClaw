package ai.javaclaw.tools;

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
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI 2.1 ends a turn after 40 calls of one tool; JavaClaw's advisors must keep running tools
 * the way they did on 2.0.
 */
class ToolCallingManagersTest {

    static final int CALLS = 60;

    @Test
    void runsMoreToolCallsInOneTurnThanSpringAiAllowsByDefault() {
        Counter counter = new Counter();

        String answer = chatClient(ToolCallingManagers.unlimited(), counter).prompt("Count").call().content();

        assertThat(counter.calls).hasValue(CALLS);
        assertThat(answer).isEqualTo("Done after " + CALLS + " calls.");
    }

    @Test
    void springAiDefaultManagerStopsTheTurnAtItsLimit() {
        Counter counter = new Counter();

        String answer = chatClient(ToolCallingManager.builder().build(), counter).prompt("Count").call().content();

        assertThat(counter.calls).hasValueLessThan(CALLS);
        assertThat(answer).contains("Tool call limit");
    }

    private static ChatClient chatClient(ToolCallingManager manager, Counter counter) {
        return ChatClient.builder(new CountingModel())
                .defaultTools(counter)
                .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                .build();
    }

    static class Counter {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Counts one step")
        int count() {
            return calls.incrementAndGet();
        }
    }

    /** Calls the count tool once per reply until it has {@link #CALLS} results, then answers. */
    static class CountingModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            long results = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .count();
            if (results >= CALLS) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Done after " + results + " calls."))));
            }
            AssistantMessage toolCall = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + results, "function", "count", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(toolCall)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
