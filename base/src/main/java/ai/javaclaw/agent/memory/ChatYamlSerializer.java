package ai.javaclaw.agent.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Serialises and deserialises a list of Spring AI {@link Message} objects to/from
 * a YAML block-list string, for use as the body of a {@link ai.javaclaw.files.YamlDocument}.
 *
 * <p>Format:
 * <pre>
 * - role: user
 *   content: "Question text"
 * - role: assistant
 *   tool_calls:
 *     - id: call_123
 *       type: function
 *       function: get_weather
 *       arguments: '{"location": "London"}'
 * - role: tool
 *   tool_call_id: call_123
 *   name: get_weather
 *   content: "Sunny, 20°C"
 * - role: assistant
 *   content: "Here is the weather..."
 * </pre>
 *
 * <p>Legacy format (one entry per message, role is the key) is still supported on read:
 * <pre>
 * - user: Question text
 * - assistant: Answer text
 * </pre>
 */
class ChatYamlSerializer {

    private static final Set<MessageType> PERSISTABLE_MESSAGES =
            Set.of(MessageType.USER, MessageType.ASSISTANT, MessageType.SYSTEM, MessageType.TOOL);

    private ChatYamlSerializer() {}

    static List<Message> deserialize(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Yaml yaml = new Yaml();
        List<Map<String, Object>> entries = yaml.load(body);
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(ChatYamlSerializer::toMessage)
                .collect(Collectors.toList());
    }

    static String serialize(List<Message> messages) {
        List<Map<String, Object>> entries = messages.stream()
                .filter(msg -> PERSISTABLE_MESSAGES.contains(msg.getMessageType()))
                .flatMap(ChatYamlSerializer::toYamlEntries)
                .collect(Collectors.toList());

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(entries);
    }

    private static Stream<Map<String, Object>> toYamlEntries(Message msg) {
        if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
            return Stream.of(toAssistantToolCallYamlEntry(am));
        }
        if (msg instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream().map(ChatYamlSerializer::toToolResponseYamlEntry);
        }
        if (msg.getText() == null) return Stream.empty();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", msg.getMessageType().getValue());
        entry.put("content", msg.getText());
        return Stream.of(entry);
    }

    private static Map<String, Object> toAssistantToolCallYamlEntry(AssistantMessage msg) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "assistant");
        if (msg.getText() != null && !msg.getText().isBlank()) entry.put("content", msg.getText());
        entry.put("tool_calls", msg.getToolCalls().stream()
                .map(ChatYamlSerializer::toToolCallYamlEntry)
                .collect(Collectors.toList()));
        return entry;
    }

    private static Map<String, String> toToolCallYamlEntry(AssistantMessage.ToolCall tc) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("id", tc.id());
        map.put("type", tc.type());
        map.put("function", tc.name());
        map.put("arguments", tc.arguments());
        return map;
    }

    private static Map<String, Object> toToolResponseYamlEntry(ToolResponseMessage.ToolResponse tr) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "tool");
        entry.put("tool_call_id", tr.id());
        entry.put("name", tr.name());
        entry.put("content", tr.responseData());
        return entry;
    }

    private static Message toMessage(Map<String, Object> entry) {
        // Support legacy format: {user: "text"} or {assistant: "text"}
        if (!entry.containsKey("role")) {
            Map.Entry<String, Object> first = entry.entrySet().iterator().next();
            return toLegacyMessage(first.getKey(), (String) first.getValue());
        }
        String role = (String) entry.get("role");
        return switch (role) {
            case "user" -> new UserMessage((String) entry.get("content"));
            case "system" -> new SystemMessage((String) entry.get("content"));
            case "assistant" -> toAssistantMessage(entry);
            case "tool" -> toToolMessage(entry);
            default -> throw new IllegalArgumentException("Unknown role in chat history: " + role);
        };
    }

    @SuppressWarnings("unchecked")
    private static AssistantMessage toAssistantMessage(Map<String, Object> entry) {
        String content = (String) entry.get("content");
        List<Map<String, String>> rawToolCalls = (List<Map<String, String>>) entry.get("tool_calls");
        if (rawToolCalls == null || rawToolCalls.isEmpty()) {
            return new AssistantMessage(content != null ? content : "");
        }
        List<AssistantMessage.ToolCall> toolCalls = toAssistantToolCalls(rawToolCalls);
        return AssistantMessage.builder()
                .content(content != null ? content : "")
                .toolCalls(toolCalls)
                .build();
    }

    private static List<AssistantMessage.ToolCall> toAssistantToolCalls(List<Map<String, String>> rawToolCalls) {
        return rawToolCalls.stream()
            .map(tc -> new AssistantMessage.ToolCall(
                tc.get("id"),
                tc.getOrDefault("type", "function"),
                tc.get("function"),
                tc.get("arguments")))
            .collect(Collectors.toList());
    }

    private static ToolResponseMessage toToolMessage(Map<String, Object> entry) {
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                (String) entry.get("tool_call_id"),
                (String) entry.get("name"),
                (String) entry.get("content"));
        return ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
    }

    private static Message toLegacyMessage(String role, String content) {
        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> throw new IllegalArgumentException("Unknown role in chat history: " + role);
        };
    }
}
