package ai.javaclaw.usage;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the token usage of every model call, each round of the tool-calling loop included.
 */
@Component
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor {

    /** Inside the tool loop, so each round is seen rather than only the loop's total. */
    public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 1;

    private final UsageRecorder recorder;

    public TokenUsageAdvisor(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (hasUsage(chatResponse)) {
            recorder.record(sessionId(request), chatResponse);
        }
        return response;
    }

    /**
     * Providers report usage incrementally while streaming (Anthropic sends the input tokens first
     * and the output tokens last), so only the last usage is recorded, once the stream completes.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AtomicReference<ChatResponse> last = new AtomicReference<>();
            return chain.nextStream(request)
                    .doOnNext(response -> {
                        ChatResponse chatResponse = response == null ? null : response.chatResponse();
                        if (hasUsage(chatResponse)) {
                            last.set(chatResponse);
                        }
                    })
                    .doOnComplete(() -> {
                        ChatResponse chatResponse = last.get();
                        if (chatResponse != null) {
                            recorder.record(sessionId(request), chatResponse);
                        }
                    });
        });
    }

    static boolean hasUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return false;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        return usage != null && (positive(usage.getPromptTokens()) || positive(usage.getCompletionTokens()));
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static String sessionId(ChatClientRequest request) {
        Object value = request.context().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
        return value == null ? null : value.toString();
    }
}
