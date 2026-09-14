package ai.javaclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports every tool the model calls, to the log and to an optional {@link ResponseListener}.
 * <p>
 * {@link ToolCallingAdvisor} runs the tool loop and does not pass the assistant chunks that carry
 * tool calls on to the outbound stream, so nothing downstream — the agent included — can see them.
 * Ordering this advisor <em>inside</em> that loop (closer to the model) is what makes them visible.
 * </p>
 * <p>
 * Stateful, so build one per request.
 * </p>
 */
public class ToolCallObservingAdvisor implements CallAdvisor, StreamAdvisor {

    /** Greater than {@link ToolCallingAdvisor#DEFAULT_ORDER}, i.e. inside the tool loop. */
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

    private static final Logger log = LoggerFactory.getLogger(ToolCallObservingAdvisor.class);
    private static final ResponseListener LOG_ONLY = ResponseListener.of(_ -> {}, () -> {}, _ -> {});

    private final String conversationId;
    private final ResponseListener listener;
    /**
     * Calls seen but not yet answered. Doubles as de-duplication: arguments stream in as partial
     * JSON across many chunks, so the same call is seen repeatedly.
     */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public ToolCallObservingAdvisor(String conversationId) {
        this(conversationId, LOG_ONLY);
    }

    /** @param listener also notified of each call and result, so a UI can render them. */
    public ToolCallObservingAdvisor(String conversationId, ResponseListener listener) {
        this.conversationId = conversationId;
        this.listener = listener;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        reportResults(request);
        return chain.nextStream(request).doOnNext(this::reportCalls);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        reportResults(request);
        ChatClientResponse response = chain.nextCall(request);
        reportCalls(response);
        return response;
    }

    /** Calls ride on the responses coming back from the model, before the loop executes them. */
    private void reportCalls(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        chatResponse.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .filter(call -> StringUtils.hasText(call.id()) && StringUtils.hasText(call.name()))
                .filter(call -> pending.add(call.id()))
                .forEach(call -> {
                    log.info("Conversation {} calls tool {}", conversationId, call.name());
                    // Arguments carry whatever the user typed and can be large — keep them off INFO.
                    log.debug("Conversation {} tool {} arguments: {}",
                            conversationId, call.name(), call.arguments());
                    listener.onToolCall(call.id(), call.name(), call.arguments());
                });
    }

    /** Their results arrive as message history on the next round of the loop. */
    private void reportResults(ChatClientRequest request) {
        request.prompt().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(result -> pending.remove(result.id()))
                .forEach(result -> {
                    log.info("Conversation {} finished tool {}", conversationId, result.name());
                    listener.onToolResult(result.id(), result.name(), result.responseData());
                });
    }

    @Override
    public String getName() {
        return "toolCallObservingAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
