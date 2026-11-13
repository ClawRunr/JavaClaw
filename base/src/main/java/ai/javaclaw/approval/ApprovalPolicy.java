package ai.javaclaw.approval;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.util.Set;

/**
 * Which tool calls need the owner's approval: every call of a tool that is not in
 * {@code agent.approval.allow}.
 * <p>
 * {@link #toolCallingManager()} applies it as Spring AI's {@link ToolCallConfirmationPolicy}: the
 * tool-calling loop pauses on a reply with such a call before any of its calls run, and the agent
 * resumes the reply with the same manager once the owner has decided. Both tool-calling advisors
 * get this manager, so static, MCP, plugin and tool-search-discovered tools are all covered.
 * Deliberately not a {@code ToolCallingManager} or policy bean, so the chat-model
 * auto-configuration never picks it up.
 */
public class ApprovalPolicy {

    private final Set<String> allow;
    private final ToolCallingManager toolCallingManager;

    public ApprovalPolicy(ApprovalProperties properties) {
        this(properties, ObservationRegistry.NOOP, null);
    }

    /** @param toolCallbackResolver resolves tools named by name only; {@code null} for Spring AI's default */
    public ApprovalPolicy(ApprovalProperties properties, ObservationRegistry observationRegistry,
                          ToolCallbackResolver toolCallbackResolver) {
        this.allow = Set.copyOf(properties.allow());
        var builder = ToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .toolCallConfirmationPolicy((toolCall, _) -> requiresApproval(toolCall.name()));
        if (toolCallbackResolver != null) {
            builder.toolCallbackResolver(toolCallbackResolver);
        }
        this.toolCallingManager = builder.build();
    }

    public boolean requiresApproval(String toolName) {
        return !allow.contains(toolName);
    }

    /** The manager the tool-calling advisors run with and the agent resumes paused replies with. */
    public ToolCallingManager toolCallingManager() {
        return toolCallingManager;
    }
}
