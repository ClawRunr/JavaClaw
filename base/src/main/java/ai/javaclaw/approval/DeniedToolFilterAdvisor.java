package ai.javaclaw.approval;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Drops tools denied by {@code agent.approval.rules.deny} from the request, so the model is never
 * offered them. Runs just outside the tool-calling advisor, which captures the options once and
 * reuses them for every round of the loop.
 */
class DeniedToolFilterAdvisor implements CallAdvisor, StreamAdvisor {

    static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 1;

    private final ApprovalPolicy policy;

    DeniedToolFilterAdvisor(ApprovalPolicy policy) {
        this.policy = policy;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(filter(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(filter(request));
    }

    private ChatClientRequest filter(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)
                || CollectionUtils.isEmpty(options.getToolCallbacks())) {
            return request;
        }
        List<ToolCallback> offered = options.getToolCallbacks().stream()
                .filter(callback -> policy.isOffered(callback.getToolDefinition().name()))
                .toList();
        if (offered.size() == options.getToolCallbacks().size()) {
            return request;
        }
        return request.mutate()
                .prompt(request.prompt().mutate().chatOptions(options.mutate().toolCallbacks(offered).build()).build())
                .build();
    }

    @Override
    public String getName() {
        return "deniedToolFilterAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
