package ai.javaclaw.approval;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A reply the tool-calling loop paused on: the model asked for tool calls, and at least one of
 * them needs the owner's approval. Calls the policy did not flag are allowed from the start; the
 * flagged ones wait for a decision each.
 * <p>
 * The reply and its tools are the server's own copy, kept from the model's response; a client
 * only ever names a request by its id and argument hash.
 */
public final class PausedTurn {

    private static final List<ApprovalDecision> DECISIONS = List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY);

    private final String conversationId;
    private final ChatResponse reply;
    private final ToolCallingChatOptions options;
    private final List<String> keys;
    /** Requests by approval id, in the model's order. */
    private final Map<String, ApprovalRequest> requests = new LinkedHashMap<>();
    /** The key of the call each request is about, by approval id. */
    private final Map<String, String> keyOf = new HashMap<>();
    /** Decisions by call key. */
    private final Map<String, ToolCallDecision> decisions = new HashMap<>();

    /**
     * @param reply    the paused reply, as the model gave it
     * @param awaiting the keys of the calls that wait for a decision
     * @param options  the turn's tool options, carrying its full tool set
     */
    public PausedTurn(String conversationId, ChatResponse reply, List<String> awaiting, ToolCallingChatOptions options) {
        this.conversationId = conversationId;
        this.reply = reply;
        this.options = options;
        AssistantMessage message = toolCallMessage(reply);
        this.keys = ToolCallDecisions.toolCallKeys(message);
        Set<String> flagged = Set.copyOf(awaiting);
        if (!keys.containsAll(flagged)) {
            throw new IllegalArgumentException("The reply has no tool calls " + awaiting + "; its keys are " + keys);
        }
        List<AssistantMessage.ToolCall> calls = message.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            String key = keys.get(i);
            if (!flagged.contains(key)) {
                decisions.put(key, ToolCallDecision.allow(key));
                continue;
            }
            String arguments = StringUtils.hasText(call.arguments()) ? call.arguments() : "{}";
            ApprovalRequest request = new ApprovalRequest(UUID.randomUUID().toString(), call.id(), conversationId,
                    call.name(), arguments, ArgumentsHash.of(call.name(), arguments), DECISIONS);
            requests.put(request.approvalId(), request);
            keyOf.put(request.approvalId(), key);
        }
    }

    /** The generation whose tool calls the loop paused on: the first with tool calls. */
    public static AssistantMessage toolCallMessage(ChatResponse reply) {
        return reply.getResults().stream()
                .map(Generation::getOutput)
                .filter(AssistantMessage::hasToolCalls)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The reply has no tool calls"));
    }

    public String conversationId() {
        return conversationId;
    }

    public ChatResponse reply() {
        return reply;
    }

    public ToolCallingChatOptions options() {
        return options;
    }

    /** Every request of this reply, decided or not, in the model's order. */
    public synchronized List<ApprovalRequest> requests() {
        return List.copyOf(requests.values());
    }

    /** The requests still waiting for a decision, in the model's order. */
    public synchronized List<ApprovalRequest> waiting() {
        return requests.values().stream()
                .filter(request -> !decisions.containsKey(keyOf.get(request.approvalId())))
                .toList();
    }

    /**
     * Records the owner's decision on one request. A decision only lands when the id names a request
     * of this reply that is still waiting, the hash matches its arguments and the decision is offered.
     *
     * @return the request it answered, or empty when it changed nothing
     */
    public synchronized Optional<ApprovalRequest> decide(String approvalId, String argumentsHash, ApprovalDecision decision) {
        ApprovalRequest request = approvalId == null ? null : requests.get(approvalId);
        if (request == null || decision == null || !request.decisions().contains(decision)
                || !request.argumentsHash().equals(argumentsHash)) {
            return Optional.empty();
        }
        String key = keyOf.get(approvalId);
        if (decisions.containsKey(key)) {
            return Optional.empty();
        }
        decisions.put(key, decision == ApprovalDecision.ALLOW_ONCE
                ? ToolCallDecision.allow(key)
                : ToolCallDecision.deny(key, Denial.NOT_APPROVED.message()));
        return Optional.of(request);
    }

    /**
     * Denies every request still waiting.
     *
     * @return the requests it denied
     */
    public synchronized List<ApprovalRequest> denyWaiting(Denial denial) {
        List<ApprovalRequest> denied = new ArrayList<>(waiting());
        denied.forEach(request -> {
            String key = keyOf.get(request.approvalId());
            decisions.put(key, ToolCallDecision.deny(key, denial.message()));
        });
        return denied;
    }

    /** Whether every call of the reply has a decision. */
    public synchronized boolean isDecided() {
        return decisions.keySet().containsAll(keys);
    }

    /** One decision per call of the reply, in the model's order. Only once {@link #isDecided()}. */
    public synchronized ToolCallDecisions decisions() {
        if (!isDecided()) {
            throw new IllegalStateException("Not every tool call of the reply is decided yet");
        }
        return new ToolCallDecisions(keys.stream().map(decisions::get).toList());
    }
}
