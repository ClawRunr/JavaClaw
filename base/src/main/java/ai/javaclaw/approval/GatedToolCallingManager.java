package ai.javaclaw.approval;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ToolCallingManager} that asks the {@link ApprovalGate} about every call before handing
 * the approved ones to the real manager. Refused calls get the refusal as their tool result, in
 * the model's original call order, so the conversation carries on.
 * <p>
 * The partial-execution-and-merge approach is adapted from spring-ai-playground's
 * {@code AgentLoopManager} (Copyright © 2025 Jemin Huh, Apache License 2.0).
 * </p>
 */
class GatedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ApprovalGate gate;

    GatedToolCallingManager(ToolCallingManager delegate, ApprovalGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage assistantMessage = chatResponse.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> !CollectionUtils.isEmpty(output.getToolCalls()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));

        Map<String, Object> toolContext = prompt.getOptions() instanceof ToolCallingChatOptions options
                ? options.getToolContext() : Map.of();
        ApprovalTurn turn = ApprovalTurn.from(toolContext).orElse(null);

        List<AssistantMessage.ToolCall> calls = assistantMessage.getToolCalls();
        List<Optional<Denial>> verdicts = new ArrayList<>(calls.size());
        List<AssistantMessage.ToolCall> approved = new ArrayList<>();
        for (AssistantMessage.ToolCall call : calls) {
            Optional<Denial> denial = gate.check(call, turn);
            verdicts.add(denial);
            if (denial.isEmpty()) {
                approved.add(call);
            }
        }

        boolean returnDirect = false;
        Iterator<ToolResponseMessage.ToolResponse> executed = List.<ToolResponseMessage.ToolResponse>of().iterator();
        if (!approved.isEmpty()) {
            AssistantMessage onlyApproved = AssistantMessage.builder()
                    .content(assistantMessage.getText())
                    .properties(assistantMessage.getMetadata())
                    .toolCalls(approved)
                    .build();
            ToolExecutionResult result = delegate.executeToolCalls(withoutTurn(prompt),
                    ChatResponse.builder().from(chatResponse).generations(List.of(new Generation(onlyApproved))).build());
            executed = lastToolResponses(result).iterator();
            // Returning a tool's output directly would hide a refusal from the model
            returnDirect = result.returnDirect() && approved.size() == calls.size();
        }

        // The delegate answers approved calls in order; slot refusals back in between them
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            Optional<Denial> denial = verdicts.get(i);
            if (denial.isPresent()) {
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        denial.get().message()));
            } else if (executed.hasNext()) {
                responses.add(executed.next());
            }
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(returnDirect).build();
    }

    private static List<ToolResponseMessage.ToolResponse> lastToolResponses(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return !history.isEmpty() && history.getLast() instanceof ToolResponseMessage message
                ? message.getResponses() : List.of();
    }

    /** Tools must never receive the turn: MCP tools could forward their context to a remote server. */
    private static Prompt withoutTurn(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || !options.getToolContext().containsKey(ApprovalTurn.TOOL_CONTEXT_KEY)) {
            return prompt;
        }
        Map<String, Object> context = new HashMap<>(options.getToolContext());
        context.remove(ApprovalTurn.TOOL_CONTEXT_KEY);
        ChatOptions stripped = options.mutate().toolContext(null).toolContext(context).build();
        return prompt.mutate().chatOptions(stripped).build();
    }
}
