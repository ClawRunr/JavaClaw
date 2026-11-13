package ai.javaclaw.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Records the tool options of a request just outside the tool loop, where they still carry the
 * turn's full tool set: the tool-search advisor narrows them only inside the loop. A paused reply
 * resumes with these, so a tool the search found can run once allowed.
 * <p>
 * Stateful, so build one per request.
 */
final class ToolOptionsCapture implements CallAdvisor, StreamAdvisor {

    /** Just outside the tool loop, inside the chat memory advisor. */
    static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 1;

    private volatile ToolCallingChatOptions options;

    /** The request's tool options, or {@code null} before the request or when it had none. */
    ToolCallingChatOptions options() {
        return options;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        record(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        record(request);
        return chain.nextStream(request);
    }

    private void record(ChatClientRequest request) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions) {
            options = toolOptions;
        }
    }

    @Override
    public String getName() {
        return "toolOptionsCapture";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
