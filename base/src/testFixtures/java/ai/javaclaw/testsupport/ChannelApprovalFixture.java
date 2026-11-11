package ai.javaclaw.testsupport;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.DefaultAgent;
import ai.javaclaw.approval.ApprovalGate;
import ai.javaclaw.approval.ApprovalProperties;
import ai.javaclaw.approval.PendingApprovals;
import ai.javaclaw.approval.ProtectedPaths;
import ai.javaclaw.channels.ApprovalMessages;
import ai.javaclaw.channels.ChannelApprovals;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A real agent behind the real approval gate, for testing the text approval flow end to end
 * without a language model: the scripted model asks for {@value #TOOL} with {@link #ARGUMENTS}
 * once, then answers with the tool's result ({@code "Done: fetched"}) or the gate's refusal.
 * The tool is not in {@code allow}, so it needs approval.
 */
public final class ChannelApprovalFixture implements AutoCloseable {

    public static final String TOOL = "fetchPage";
    public static final String ARGUMENTS = "{\"url\":\"https://example.com\"}";
    public static final String TOOL_RESULT = "fetched";
    public static final String ANSWER = "Done: " + TOOL_RESULT;
    private static final Pattern SHORT_ID = Pattern.compile("/approve(?:\\\\)?_([a-z0-9]{6})");

    private final PendingApprovals pending = new PendingApprovals();
    private final List<String> toolRuns = new CopyOnWriteArrayList<>();
    private final List<String> modelSaw = new CopyOnWriteArrayList<>();
    private final Agent agent;
    private final ChannelApprovals approvals;

    public ChannelApprovalFixture() {
        this(Duration.ofSeconds(30), ARGUMENTS);
    }

    /**
     * @param timeout   how long an approval waits
     * @param arguments what the model passes to {@value #TOOL}
     */
    public ChannelApprovalFixture(Duration timeout, String arguments) {
        ApprovalGate gate = new ApprovalGate(new ApprovalProperties(timeout, null, null, null),
                new ProtectedPaths(Path.of("application.private.yaml"), List.of(), Path.of("workspace")),
                pending, Clock.systemUTC());
        this.agent = new DefaultAgent(ChatClient.builder(new ScriptedModel(arguments))
                .defaultTools(new FetchTool())
                .defaultAdvisors(gate.deniedToolFilter(), ToolCallingAdvisor.builder()
                        .toolCallingManager(gate.toolCallingManager(ToolCallingManager.builder().build()))
                        .build())
                .build());
        this.approvals = new ChannelApprovals(agent, pending);
    }

    /**
     * Approvals whose turns run on the calling thread, so a test sees the whole exchange when the
     * channel returns. Only for turns that never wait for an approval: one that did would block the
     * thread its answer has to arrive on.
     */
    public static ChannelApprovals onCallingThread(Agent agent) {
        return new ChannelApprovals(agent, new PendingApprovals(),
                new ApprovalMessages(Clock.systemDefaultZone()),
                Clock.systemDefaultZone(), Runnable::run);
    }

    public Agent agent() {
        return agent;
    }

    public ChannelApprovals approvals() {
        return approvals;
    }

    public PendingApprovals pending() {
        return pending;
    }

    /** The arguments of every run of {@value #TOOL}. */
    public List<String> toolRuns() {
        return toolRuns;
    }

    /** Every user message the model was given. */
    public List<String> modelSaw() {
        return modelSaw;
    }

    /** The short id in an approval request message ({@code /approve_a1b2c3}, in any channel's markup). */
    public static String shortId(String requestMessage) {
        Matcher matcher = SHORT_ID.matcher(requestMessage);
        if (!matcher.find()) {
            throw new AssertionError("No /approve_<id> in: " + requestMessage);
        }
        return matcher.group(1);
    }

    /** Waits up to 10 seconds for {@code condition}. */
    public static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 10 seconds");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public void close() {
        approvals.close();
    }

    private final class FetchTool implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(TOOL).description("Fetches a web page")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            toolRuns.add(toolInput);
            return TOOL_RESULT;
        }
    }

    private final class ScriptedModel implements ChatModel {

        private final String arguments;

        ScriptedModel(String arguments) {
            this.arguments = arguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(Message::getText)
                    .forEach(modelSaw::add);
            String lastResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                    .reduce((a, b) -> b)
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .orElse(null);
            if (lastResult == null) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", TOOL, arguments)))
                        .build())));
            }
            String text = lastResult.equals(TOOL_RESULT) ? ANSWER : "Not done: " + lastResult;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
