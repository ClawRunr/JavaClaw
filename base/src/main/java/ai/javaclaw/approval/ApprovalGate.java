package ai.javaclaw.approval;

import ai.javaclaw.approval.ApprovalListener.ApprovalOutcome;
import ai.javaclaw.approval.PendingApprovals.Pending;
import ai.javaclaw.approval.PendingApprovals.Resolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides, call by call, whether a tool the model asked for may run: by rule, by protected action,
 * or by asking the owner and waiting for the answer.
 * <p>
 * Every tool call of the main agent passes through here: {@link #toolCallingManager} wraps the
 * {@link ToolCallingManager} of both tool-calling advisors, so static, MCP, plugin and
 * tool-search-discovered tools are all gated. Deliberately not a {@code ToolCallingManager} bean
 * itself, so chat-model auto-configuration never picks it up.
 * </p>
 * <p>
 * Interception pattern adapted from spring-ai-playground's {@code AgentLoopManager} and
 * {@code HitlApprovalInterceptor} (Copyright © 2025 Jemin Huh, Apache License 2.0). Unlike the
 * original, a missing or failing question handler never runs the tool, and an approval is bound to
 * a hash of the exact arguments.
 * </p>
 */
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    /** Tools whose card should show the directory relative paths resolve against. */
    private static final Set<String> FILE_TOOLS = Set.of("Read", "Write", "Edit", "Glob", "Grep", "ListDirectory", "Bash");
    private static final String SUBAGENT_TOOL = "Task";
    private static final List<ApprovalDecision> DECISIONS = List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApprovalProperties properties;
    private final ApprovalPolicy policy;
    private final ToolRiskRegistry risks;
    private final ProtectedActions protectedActions;
    private final PendingApprovals pendingApprovals;
    private final Clock clock;

    public ApprovalGate(ApprovalProperties properties, ToolRiskRegistry risks, ProtectedActions protectedActions,
                        PendingApprovals pendingApprovals, Clock clock) {
        this.properties = properties;
        this.policy = new ApprovalPolicy(properties.rules());
        this.risks = risks;
        this.protectedActions = protectedActions;
        this.pendingApprovals = pendingApprovals;
        this.clock = clock;
    }

    /** Wraps {@code delegate} so every call it would execute is gated first. */
    public ToolCallingManager toolCallingManager(ToolCallingManager delegate) {
        return new GatedToolCallingManager(delegate, this);
    }

    /**
     * Wraps the application's {@link ToolCallingManager} (with its observation and callback
     * resolver) when there is exactly one, else a default one.
     */
    public ToolCallingManager toolCallingManager(ObjectProvider<ToolCallingManager> delegates) {
        return toolCallingManager(delegates.getIfUnique(() -> ToolCallingManager.builder().build()));
    }

    /** Removes denied tools from the request before the model sees them; order it outside the tool loop. */
    public Advisor deniedToolFilter() {
        return new DeniedToolFilterAdvisor(policy);
    }

    boolean isOffered(String toolName) {
        return policy.isOffered(toolName);
    }

    /**
     * Decides one call, asking and waiting if needed.
     *
     * @param turn the turn's approval state, or {@code null} when the request carried none
     * @return why the call must not run, or empty when it may
     */
    Optional<Denial> check(AssistantMessage.ToolCall call, ApprovalTurn turn) {
        String toolName = call.name();
        String arguments = StringUtils.hasText(call.arguments()) ? call.arguments() : "{}";
        Optional<ToolRisk> risk = risks.riskOf(toolName);
        Optional<String> protectedReason = protectedActions.reason(toolName, arguments, risk);

        ApprovalPolicy.Verdict verdict = policy.evaluate(toolName, risk, protectedReason.isPresent());
        if (verdict == ApprovalPolicy.Verdict.DENY) {
            return deny(call, turn, null, new Denial(Denial.Reason.RULE, "it is blocked by the agent.approval.rules.deny rule"));
        }
        if (turn != null) {
            Optional<Denial> earlier = turn.earlierDenial(toolName);
            if (earlier.isPresent()) {
                Denial denial = earlier.get();
                return deny(call, turn, null, new Denial(denial.reason(),
                        "it was already refused earlier in this turn (" + denial.detail() + ")"));
            }
        }
        if (verdict == ApprovalPolicy.Verdict.RUN) {
            return Optional.empty();
        }
        return ask(call, turn, arguments, risk, protectedReason);
    }

    private Optional<Denial> ask(AssistantMessage.ToolCall call, ApprovalTurn turn, String arguments,
                                 Optional<ToolRisk> risk, Optional<String> protectedReason) {
        if (turn == null || turn.questions().isEmpty()) {
            return fallback(call, turn, protectedReason, "it needs approval and nobody can be asked here");
        }
        boolean canAsk;
        try {
            canAsk = turn.questions().get().canAsk(turn.conversationId());
        } catch (RuntimeException e) {
            log.warn("Approval handler failed for conversation {}; refusing {}", turn.conversationId(), call.name(), e);
            // A failing handler never runs the tool, whatever the fallback says
            return deny(call, turn, null, new Denial(Denial.Reason.NOBODY_TO_ASK,
                    "it needs approval and the approval prompt could not be shown"));
        }
        if (!canAsk) {
            return fallback(call, turn, protectedReason,
                    "it needs approval and no web chat client is connected to this conversation");
        }

        Duration timeout = properties.timeout();
        ApprovalRequest request = new ApprovalRequest(
                approvalId(call), call.id(), turn.conversationId(), call.name(), arguments, risk.orElse(null),
                ArgumentsHash.of(call.name(), arguments), DECISIONS, details(call.name(), arguments),
                protectedReason.orElse(null), clock.instant().plus(timeout));

        Pending entry = pendingApprovals.open(request);
        try {
            turn.listener().requested(request);
        } catch (RuntimeException e) {
            pendingApprovals.discard(entry);
            log.warn("Could not show approval {} for {}", request.approvalId(), call.name(), e);
            return deny(call, turn, request.approvalId(), new Denial(Denial.Reason.NOBODY_TO_ASK,
                    "it needs approval and the approval prompt could not be shown"));
        }
        log.info("Conversation {} waits up to {} for approval of {} ({})", turn.conversationId(), timeout,
                call.name(), request.approvalId());

        Resolution resolution = await(entry, timeout);
        return switch (resolution) {
            case Resolution.Decided(ApprovalDecision decision) when decision == ApprovalDecision.ALLOW_ONCE -> {
                log.info("Conversation {}: {} approved once ({})", turn.conversationId(), call.name(), request.approvalId());
                notify(turn, new ApprovalOutcome(request.approvalId(), call.id(), call.name(), null));
                yield Optional.empty();
            }
            case Resolution.Decided ignored ->
                    deny(call, turn, request.approvalId(), new Denial(Denial.Reason.USER, "the user denied it"));
            case Resolution.Cancelled(Denial denial) -> deny(call, turn, request.approvalId(), denial);
        };
    }

    private Resolution await(Pending entry, Duration timeout) {
        Resolution timedOut = new Resolution.Cancelled(new Denial(Denial.Reason.TIMEOUT,
                "nobody approved it within " + format(timeout)));
        try {
            return entry.answer().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // A decision may land between the timeout and here; whoever completes first wins
            entry.answer().complete(timedOut);
            return entry.answer().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry.answer().complete(new Resolution.Cancelled(new Denial(Denial.Reason.NOBODY_TO_ASK,
                    "the request was stopped before anyone answered")));
            return entry.answer().join();
        } catch (ExecutionException e) {
            return new Resolution.Cancelled(new Denial(Denial.Reason.NOBODY_TO_ASK,
                    "the approval prompt failed"));
        } finally {
            pendingApprovals.discard(entry);
        }
    }

    /**
     * Nobody can be asked: {@code ask-fallback} decides, except that a protected action is always
     * refused, since running it unasked could switch the gate off or widen what the agent can reach.
     */
    private Optional<Denial> fallback(AssistantMessage.ToolCall call, ApprovalTurn turn,
                                      Optional<String> protectedReason, String detail) {
        if (protectedReason.isPresent()) {
            return deny(call, turn, null, new Denial(Denial.Reason.NOBODY_TO_ASK,
                    detail + ", and it " + protectedReason.get() + ", which always needs approval"));
        }
        if (properties.askFallback() == ApprovalProperties.AskFallback.ALLOW) {
            log.info("Running {} without approval: nobody can be asked and agent.approval.ask-fallback=allow", call.name());
            return Optional.empty();
        }
        return deny(call, turn, null, new Denial(Denial.Reason.NOBODY_TO_ASK, detail));
    }

    private Optional<Denial> deny(AssistantMessage.ToolCall call, ApprovalTurn turn, String approvalId, Denial denial) {
        log.info("Refused tool {}: {} ({})", call.name(), denial.detail(), denial.reason().wireValue());
        if (turn != null) {
            turn.recordDenial(call.name(), denial);
        }
        notify(turn, new ApprovalOutcome(approvalId, call.id(), call.name(), denial));
        return Optional.of(denial);
    }

    private static void notify(ApprovalTurn turn, ApprovalOutcome outcome) {
        if (turn == null) {
            return;
        }
        try {
            turn.listener().resolved(outcome);
        } catch (RuntimeException e) {
            log.warn("Approval listener failed for {}", outcome.toolName(), e);
        }
    }

    private String approvalId(AssistantMessage.ToolCall call) {
        // Some providers send blank or repeated ids; the approval key must be unique while pending
        String id = call.id();
        return StringUtils.hasText(id) && !pendingApprovals.isPending(id) ? id : UUID.randomUUID().toString();
    }

    /** Context the card shows next to the raw arguments. */
    private static Map<String, String> details(String toolName, String arguments) {
        Map<String, String> details = new LinkedHashMap<>();
        if (FILE_TOOLS.contains(toolName)) {
            details.put("workingDirectory", System.getProperty("user.dir"));
        }
        if (SUBAGENT_TOOL.equals(toolName)) {
            Map<?, ?> args = parseObject(arguments);
            putIfText(details, "subagentType", args.get("subagent_type"));
            putIfText(details, "prompt", args.get("prompt"));
            if (Boolean.TRUE.equals(args.get("run_in_background"))) {
                details.put("runInBackground", "true");
            }
        }
        return details;
    }

    private static Map<?, ?> parseObject(String arguments) {
        try {
            return JSON.readValue(arguments, Object.class) instanceof Map<?, ?> map ? map : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private static void putIfText(Map<String, String> into, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) {
            into.put(key, text);
        }
    }

    private static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds >= 60 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
