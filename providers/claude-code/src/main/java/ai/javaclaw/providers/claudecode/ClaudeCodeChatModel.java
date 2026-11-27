package ai.javaclaw.providers.claudecode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A {@link ChatModel} backed by the user's Claude Pro/Max subscription through the official Claude
 * Code CLI (a port of the Hermes "Claude Subscription DirectSDK" provider).
 * <p>
 * Every call starts a fresh {@code claude -p} process that generates exactly one assistant turn.
 * Tool calls come back as ordinary Spring AI tool calls, so JavaClaw's own {@code ToolCallingAdvisor}
 * runs the tools and the agent loop. JavaClaw never handles the subscription credential: the CLI logs
 * in, refreshes and authenticates on its own.
 */
public class ClaudeCodeChatModel implements ChatModel {

    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    /** Longest silence from Claude Code before a call is abandoned; it restarts with every event. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String defaultModel;
    private final String command;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final ToolCallingManager toolCallingManager;

    private ClaudeCodeChatModel(Builder builder) {
        this.defaultModel = builder.model;
        this.command = builder.command;
        this.environment = builder.environment;
        this.timeout = builder.timeout;
        this.toolCallingManager = builder.toolCallingManager;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ClaudeCodeRequest request = ClaudeCodeRequest.from(prompt, defaultModel, toolCallingManager);
        ClaudeCodeCall.Outcome outcome = new ClaudeCodeCall(request, command, environment, timeout).run(text -> {
        });
        return response(outcome, outcome.text(), request.model);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.create(sink -> {
            ClaudeCodeRequest request;
            try {
                request = ClaudeCodeRequest.from(prompt, defaultModel, toolCallingManager);
            } catch (RuntimeException e) {
                sink.error(e);
                return;
            }
            ClaudeCodeCall call = new ClaudeCodeCall(request, command, environment, timeout);
            sink.onCancel(call::cancel);
            Thread.ofVirtual().name("claude-code-call").start(() -> {
                try {
                    ClaudeCodeCall.Outcome outcome = call.run(text -> sink.next(textChunk(text, request.model)));
                    // Text was streamed already; the final chunk carries tool calls, usage and the native turn.
                    sink.next(response(outcome, "", request.model));
                    sink.complete();
                } catch (RuntimeException e) {
                    // A cancelled subscriber caused the failure; nobody is left to tell.
                    if (!sink.isCancelled()) {
                        sink.error(e);
                    }
                }
            });
        });
    }

    /** {@code ChatClient} attaches tools only to options built from a {@link ToolCallingChatOptions} builder. */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model(defaultModel).build();
    }

    private static ChatResponse textChunk(String text, String model) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).build());
    }

    private static ChatResponse response(ClaudeCodeCall.Outcome outcome, String content, String model) {
        List<AssistantMessage.ToolCall> toolCalls = outcome.toolCalls().stream()
                .map(call -> new AssistantMessage.ToolCall(call.get("id").stringValue(), "function",
                        call.get("name").stringValue(), JSON.writeValueAsString(call.get("input"))))
                .toList();
        AssistantMessage message = AssistantMessage.builder()
                .content(content)
                .properties(Map.of(ClaudeCodeRequest.NATIVE_TURN, JSON.writeValueAsString(outcome.nativeTurn())))
                .toolCalls(toolCalls)
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(outcome.finishReason())
                .build();
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(outcome.id())
                .model(model)
                .usage(usage(outcome.usage()))
                .build();
        return new ChatResponse(List.of(new Generation(message, generationMetadata)), metadata);
    }

    /** Prompt tokens include cache reads and writes, as the Anthropic API counts them separately. */
    private static DefaultUsage usage(JsonNode usage) {
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        int prompt = (int) (usage.path("input_tokens").asLong(0) + cacheRead + cacheWrite);
        int completion = usage.path("output_tokens").asInt(0);
        return new DefaultUsage(prompt, completion, prompt + completion,
                JSON.convertValue(usage, Map.class), cacheRead, cacheWrite);
    }

    public static final class Builder {

        private String model = DEFAULT_MODEL;
        private String command;
        private Map<String, String> environment;
        private Duration timeout = DEFAULT_TIMEOUT;
        private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        private Builder() {
        }

        /** The model or alias ({@code sonnet}, {@code opus}, {@code haiku}, {@code fable}, or a full id). */
        public Builder model(String model) {
            if (model != null && !model.isBlank()) {
                this.model = model.trim();
            }
            return this;
        }

        /** The {@code claude} executable; by default {@link ClaudeCodeCli#COMMAND_ENV}, PATH, then the usual install directories. */
        public Builder command(String command) {
            this.command = command;
            return this;
        }

        /**
         * A complete environment for Claude Code instead of JavaClaw's own. Meant for controlled test
         * fixtures: with an explicit environment, credential overrides are not refused.
         */
        public Builder environment(Map<String, String> environment) {
            this.environment = environment == null ? null : Map.copyOf(environment);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public ClaudeCodeChatModel build() {
            return new ClaudeCodeChatModel(this);
        }
    }
}
