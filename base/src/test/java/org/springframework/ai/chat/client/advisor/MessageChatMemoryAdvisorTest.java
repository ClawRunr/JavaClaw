package org.springframework.ai.chat.client.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageChatMemoryAdvisorTest {

    // -----------------------------------------------------------------------
    // Plain text messages
    // -----------------------------------------------------------------------

    @Test
    void deduplicateRetainsAllWhenNoOverlap() {
        List<Message> memory = List.of(new UserMessage("Hi"));
        List<Message> instructions = List.of(new AssistantMessage("Hello!"));

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(memory, instructions);

        assertThat(result).hasSize(2);
    }

    @Test
    void deduplicateRemovesDuplicateUserMessage() {
        List<Message> memory = List.of(new UserMessage("Hi"));
        List<Message> instructions = List.of(new UserMessage("Hi"));

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(memory, instructions);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("Hi");
    }

    @Test
    void deduplicatePrefersMemoryCopyWhenDuplicated() {
        // Memory copy has extra metadata; instruction copy does not — memory wins
        Message fromMemory = UserMessage.builder().text("Hi").metadata(Map.of("extra", "data")).build();
        Message fromInstruction = new UserMessage("Hi");

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(List.of(fromMemory), List.of(fromInstruction));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(fromMemory);
    }

    @Test
    void deduplicatePreservesOrderMemoryFirst() {
        List<Message> memory = List.of(new UserMessage("Q1"), new AssistantMessage("A1"));
        List<Message> instructions = List.of(new UserMessage("Q2"));

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(memory, instructions);

        assertThat(result).extracting(Message::getText).containsExactly("Q1", "A1", "Q2");
    }

    // -----------------------------------------------------------------------
    // AssistantMessage with tool calls — the core bug scenario
    // -----------------------------------------------------------------------

    @Test
    void deduplicateRemovesDuplicateToolCallMessage() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_123", "function", "get_weather", "{\"location\":\"London\"}");

        // Persisted copy (from ChatYamlSerializer): textContent = ""
        AssistantMessage fromMemory = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();

        // In-flight copy (from LLM): textContent = null
        AssistantMessage fromInstruction = AssistantMessage.builder()
                .toolCalls(List.of(toolCall))
                .build();

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(
                List.of(fromMemory), List.of(fromInstruction));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(fromMemory);
    }

    @Test
    void deduplicateKeepsBothToolCallMessagesWhenArgsDiffer() {
        AssistantMessage london = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "get_weather", "{\"location\":\"London\"}")))
                .build();
        AssistantMessage paris = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_2", "function", "get_weather", "{\"location\":\"Paris\"}")))
                .build();

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(List.of(london), List.of(paris));

        assertThat(result).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // ToolResponseMessage
    // -----------------------------------------------------------------------

    @Test
    void deduplicateRemovesDuplicateToolResponseMessage() {
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                "call_123", "get_weather", "Sunny, 20°C");

        ToolResponseMessage fromMemory = ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
        ToolResponseMessage fromInstruction = ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(
                List.of(fromMemory), List.of(fromInstruction));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(fromMemory);
    }

    // -----------------------------------------------------------------------
    // Full tool-call roundtrip — reproduces the original bug
    // -----------------------------------------------------------------------

    @Test
    void deduplicateFullToolCallConversationDoesNotDuplicate() {
        // Simulates a second turn where the memory already contains the full tool-call
        // sequence and the new instructions re-supply those same messages.
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_abc", "function", "get_events", "{\"user_google_calendar_id\":\"x\"}");

        // Memory: stored copies (textContent = "" from YAML deserialization)
        AssistantMessage memAssistant = AssistantMessage.builder().content("").toolCalls(List.of(tc)).build();
        ToolResponseMessage memTool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_abc", "get_events", "[]")))
                .build();

        // Instructions: in-flight copies from LLM (textContent = null)
        AssistantMessage inFlightAssistant = AssistantMessage.builder().toolCalls(List.of(tc)).build();
        ToolResponseMessage inFlightTool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_abc", "get_events", "[]")))
                .build();
        UserMessage newQuestion = new UserMessage("What's the weather tomorrow?");

        List<Message> result = MessageChatMemoryAdvisor.deduplicate(
                List.of(memAssistant, memTool),
                List.of(inFlightAssistant, inFlightTool, newQuestion));

        assertThat(result).hasSize(3); // assistant tool call + tool response + new question — no duplicates
    }
}
