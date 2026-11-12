package ai.javaclaw.approval;

import ai.javaclaw.approval.ApprovalListener.ApprovalOutcome;
import ai.javaclaw.approval.PendingApprovals.Pending;
import ai.javaclaw.approval.PendingApprovals.Resolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides, call by call, whether a tool the model asked for may run: tools in
 * {@code agent.approval.allow} run, every other call asks the owner and waits for the answer. A
 * call is denied when the owner denies it, nobody answers in time, or nobody can be asked.
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

    private static final List<ApprovalDecision> DECISIONS = List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY);

    private final Duration timeout;
    private final Set<String> allow;
    private final PendingApprovals pendingApprovals;
    private final Clock clock;

    public ApprovalGate(ApprovalProperties properties, PendingApprovals pendingApprovals, Clock clock) {
        this.timeout = properties.timeout();
        this.allow = Set.copyOf(properties.allow());
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

    /**
     * Decides one call, asking and waiting if needed.
     *
     * @param turn the turn's approval state, or {@code null} when the request carried none
     * @return why the call must not run, or empty when it may
     */
    Optional<Denial> check(AssistantMessage.ToolCall call, ApprovalTurn turn) {
        if (allow.contains(call.name())) {
            return Optional.empty();
        }
        String arguments = StringUtils.hasText(call.arguments()) ? call.arguments() : "{}";
        return ask(call, turn, arguments);
    }

    private Optional<Denial> ask(AssistantMessage.ToolCall call, ApprovalTurn turn, String arguments) {
        if (turn == null || turn.questions().isEmpty()) {
            return deny(call, turn, null, Denial.NOT_APPROVED);
        }
        boolean canAsk;
        try {
            canAsk = turn.questions().get().canAsk(turn.conversationId());
        } catch (RuntimeException e) {
            log.warn("Approval handler failed for conversation {}; refusing {}", turn.conversationId(), call.name(), e);
            canAsk = false;
        }
        if (!canAsk) {
            return deny(call, turn, null, Denial.NOT_APPROVED);
        }

        ApprovalRequest request = new ApprovalRequest(
                approvalId(call), call.id(), turn.conversationId(), call.name(), arguments,
                ArgumentsHash.of(call.name(), arguments), DECISIONS,
                clock.instant().plus(timeout));

        Pending entry = pendingApprovals.open(request);
        try {
            turn.listener().requested(request);
        } catch (RuntimeException e) {
            pendingApprovals.discard(entry);
            log.warn("Could not show approval {} for {}", request.approvalId(), call.name(), e);
            return deny(call, turn, request.approvalId(), Denial.NOT_APPROVED);
        }
        log.info("Conversation {} waits up to {} for approval of {} ({})", turn.conversationId(), timeout,
                call.name(), request.approvalId());

        Resolution resolution = await(entry);
        return switch (resolution) {
            case Resolution.Decided(ApprovalDecision decision) when decision == ApprovalDecision.ALLOW_ONCE -> {
                log.info("Conversation {}: {} approved once ({})", turn.conversationId(), call.name(), request.approvalId());
                notify(turn, new ApprovalOutcome(request.approvalId(), call.id(), call.name(), null));
                yield Optional.empty();
            }
            case Resolution.Decided ignored ->
                    deny(call, turn, request.approvalId(), Denial.NOT_APPROVED);
            case Resolution.Cancelled(Denial denial) -> deny(call, turn, request.approvalId(), denial);
        };
    }

    private Resolution await(Pending entry) {
        try {
            return entry.answer().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // A decision may land between the timeout and here; whoever completes first wins
            entry.answer().complete(new Resolution.Cancelled(Denial.TIMED_OUT));
            return entry.answer().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry.answer().complete(new Resolution.Cancelled(Denial.NOT_APPROVED));
            return entry.answer().join();
        } catch (ExecutionException e) {
            return new Resolution.Cancelled(Denial.NOT_APPROVED);
        } finally {
            pendingApprovals.discard(entry);
        }
    }

    private Optional<Denial> deny(AssistantMessage.ToolCall call, ApprovalTurn turn, String approvalId, Denial denial) {
        log.info("Refused tool {} ({})", call.name(), denial);
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

}
