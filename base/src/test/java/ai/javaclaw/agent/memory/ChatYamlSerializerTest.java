package ai.javaclaw.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatYamlSerializerTest {

    // -----------------------------------------------------------------------
    // serialize — basic messages
    // -----------------------------------------------------------------------

    @Test
    void serializesUserMessage() {
        String yaml = ChatYamlSerializer.serialize(List.of(new UserMessage("Hello!")));

        assertThat(yaml).contains("role: user").contains("content: Hello!");
    }

    @Test
    void serializesAssistantTextMessage() {
        String yaml = ChatYamlSerializer.serialize(List.of(new AssistantMessage("Hi there!")));

        assertThat(yaml).contains("role: assistant").contains("content: Hi there!");
    }

    @Test
    void serializesSystemMessage() {
        String yaml = ChatYamlSerializer.serialize(List.of(new SystemMessage("You are helpful.")));

        assertThat(yaml).contains("role: system").contains("content: You are helpful.");
    }

    // -----------------------------------------------------------------------
    // serialize — tool calls
    // -----------------------------------------------------------------------

    @Test
    void serializesAssistantMessageWithToolCalls() {
        AssistantMessage assistantWithToolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_123", "function", "get_weather", "{\"location\":\"London\"}")))
                .build();

        String yaml = ChatYamlSerializer.serialize(List.of(assistantWithToolCall));

        assertThat(yaml)
                .contains("role: assistant")
                .contains("tool_calls:")
                .contains("id: call_123")
                .contains("function: get_weather")
                .contains("arguments:");
    }

    @Test
    void serializesToolResponseMessage() {
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call_123", "get_weather", "Sunny, 20 degrees")))
                .build();

        String yaml = ChatYamlSerializer.serialize(List.of(toolResponse));

        assertThat(yaml)
                .contains("role: tool")
                .contains("tool_call_id: call_123")
                .contains("name: get_weather")
                .contains("Sunny, 20 degrees");
    }

    @Test
    void serializesToolResponseMessageWithMultipleResponses() {
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_1", "tool_a", "result_a"),
                        new ToolResponseMessage.ToolResponse("call_2", "tool_b", "result_b")))
                .build();

        String yaml = ChatYamlSerializer.serialize(List.of(toolResponse));

        // Each ToolResponse is emitted as a separate entry
        assertThat(yaml)
                .contains("tool_call_id: call_1")
                .contains("tool_call_id: call_2");
    }

    // -----------------------------------------------------------------------
    // deserialize — basic messages
    // -----------------------------------------------------------------------

    @Test
    void deserializesUserMessage() {
        String yaml = "- role: user\n  content: Hello!\n";

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("Hello!");
    }

    @Test
    void deserializesAssistantTextMessage() {
        String yaml = "- role: assistant\n  content: Hi there!\n";

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("Hi there!");
    }

    @Test
    void deserializesSystemMessage() {
        String yaml = "- role: system\n  content: You are helpful.\n";

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("You are helpful.");
    }

    // -----------------------------------------------------------------------
    // deserialize — tool calls and responses
    // -----------------------------------------------------------------------

    @Test
    void deserializesAssistantMessageWithToolCalls() {
        String yaml = """
                - role: assistant
                  tool_calls:
                  - id: call_123
                    type: function
                    function: get_weather
                    arguments: '{"location":"London"}'
                """;

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(1);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertThat(assistant.hasToolCalls()).isTrue();
        assertThat(assistant.getToolCalls()).hasSize(1);
        AssistantMessage.ToolCall tc = assistant.getToolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_123");
        assertThat(tc.type()).isEqualTo("function");
        assertThat(tc.name()).isEqualTo("get_weather");
        assertThat(tc.arguments()).isEqualTo("{\"location\":\"London\"}");
    }

    @Test
    void deserializesToolResponseMessage() {
        String yaml = """
                - role: tool
                  tool_call_id: call_123
                  name: get_weather
                  content: Sunny, 20 degrees
                """;

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(1);
        ToolResponseMessage tool = (ToolResponseMessage) messages.get(0);
        assertThat(tool.getResponses()).hasSize(1);
        ToolResponseMessage.ToolResponse tr = tool.getResponses().get(0);
        assertThat(tr.id()).isEqualTo("call_123");
        assertThat(tr.name()).isEqualTo("get_weather");
        assertThat(tr.responseData()).isEqualTo("Sunny, 20 degrees");
    }

    // -----------------------------------------------------------------------
    // roundtrip
    // -----------------------------------------------------------------------

    @Test
    void roundtripFullToolCallConversation() {
        AssistantMessage assistantWithToolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_123", "function", "get_weather", "{\"location\":\"London\"}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call_123", "get_weather", "Sunny, 20 degrees")))
                .build();
        AssistantMessage finalAnswer = new AssistantMessage("It is sunny and 20 degrees in London.");

        List<Message> original = List.of(
                new UserMessage("What's the weather in London?"),
                assistantWithToolCall,
                toolResponse,
                finalAnswer);

        String yaml = ChatYamlSerializer.serialize(original);
        List<Message> loaded = ChatYamlSerializer.deserialize(yaml);

        assertThat(loaded).hasSize(4);

        assertThat(loaded.get(0)).isInstanceOf(UserMessage.class);
        assertThat(loaded.get(0).getText()).isEqualTo("What's the weather in London?");

        AssistantMessage loadedAssistant = (AssistantMessage) loaded.get(1);
        assertThat(loadedAssistant.hasToolCalls()).isTrue();
        assertThat(loadedAssistant.getToolCalls().get(0).id()).isEqualTo("call_123");
        assertThat(loadedAssistant.getToolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(loadedAssistant.getToolCalls().get(0).arguments()).isEqualTo("{\"location\":\"London\"}");

        ToolResponseMessage loadedTool = (ToolResponseMessage) loaded.get(2);
        assertThat(loadedTool.getResponses().get(0).id()).isEqualTo("call_123");
        assertThat(loadedTool.getResponses().get(0).name()).isEqualTo("get_weather");
        assertThat(loadedTool.getResponses().get(0).responseData()).isEqualTo("Sunny, 20 degrees");

        assertThat(loaded.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(loaded.get(3).getText()).isEqualTo("It is sunny and 20 degrees in London.");
    }

    // -----------------------------------------------------------------------
    // backward compatibility — legacy format
    // -----------------------------------------------------------------------

    @Test
    void deserializesLegacyFormat() {
        String yaml = "- user: Hello!\n- assistant: Hi there!\n";

        List<Message> messages = ChatYamlSerializer.deserialize(yaml);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("Hello!");
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("Hi there!");
    }
}
