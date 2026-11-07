package ai.javaclaw.channels;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.approval.PendingApprovals;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool approvals over plain text, for messaging channels (Telegram, Discord, WhatsApp, Teams).
 *
 * <p>A channel hands every message from its allowed user to {@link #handleCommand} first. An
 * {@code /approve <id>} or {@code /deny <id>} is answered here and never reaches the model; anything
 * else goes to {@link #respond}, which runs the agent turn in the background. When the turn needs
 * approval, the channel is sent the exact call and a 6-character id, and the turn waits for the
 * owner to reply with one of the two commands.
 *
 * <p>Turns run off the channel's receiving thread on purpose: every channel takes its messages one
 * at a time, so a turn waiting on that thread could never receive the {@code /approve} it waits
 * for. Turns of one conversation still run one at a time, in order.
 *
 * <p>The approval itself is the gate's: the short id only names a pending approval, which is
 * decided through {@link PendingApprovals#decide} with its conversation and argument hash, once.
 */
@Component
public class ChannelApprovals {

    private static final Logger log = LoggerFactory.getLogger(ChannelApprovals.class);

    /** {@code /approve a1b2c3}, {@code /deny a1b2c3}; Telegram may add the bot's name to a command. */
    private static final Pattern COMMAND =
            Pattern.compile("^\\s*/(approve|deny)(?:@\\S+)?(?:\\s+(\\S+))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    static final int SHORT_ID_LENGTH = 6;
    /** Longest argument text (and subagent prompt) a request message shows in full. */
    static final int MAX_SHOWN_CHARS = 600;

    private final Agent agent;
    private final PendingApprovals pendingApprovals;
    private final Clock clock;
    private final Executor turnExecutor;
    private final SecureRandom random = new SecureRandom();
    /** Waiting approvals by short id. */
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();
    /** The last queued turn of each conversation, so the next one runs after it. */
    private final Map<String, CompletableFuture<Void>> lastTurn = new ConcurrentHashMap<>();

    private record Asked(String shortId, ApprovalRequest request, Consumer<String> send) {
    }

    @Autowired
    public ChannelApprovals(Agent agent, PendingApprovals pendingApprovals) {
        this(agent, pendingApprovals, Clock.systemUTC(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("channel-turn-", 0).factory()));
    }

    /** @param turnExecutor runs agent turns; it must not be the thread messages arrive on */
    public ChannelApprovals(Agent agent, PendingApprovals pendingApprovals, Clock clock, Executor turnExecutor) {
        this.agent = agent;
        this.pendingApprovals = pendingApprovals;
        this.clock = clock;
        this.turnExecutor = turnExecutor;
    }

    @PreDestroy
    public void close() {
        if (turnExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /**
     * Answers an {@code /approve <id>} or {@code /deny <id>} from the channel's allowed user.
     * Call it before {@link #respond}, on the thread the message arrived on.
     *
     * @param conversationId the conversation the command was written in
     * @param reply          sends a short reply to that conversation
     * @return {@code true} when the text was such a command, which must then not reach the model
     */
    public boolean handleCommand(String conversationId, String text, Consumer<String> reply) {
        Matcher command = text == null ? null : COMMAND.matcher(text);
        if (command == null || !command.matches()) {
            return false;
        }
        boolean approve = command.group(1).equalsIgnoreCase("approve");
        String id = command.group(2);
        if (id == null) {
            reply.accept("Reply /approve <id> or /deny <id> with the id from the approval request.");
            return true;
        }
        Asked waiting = asked.get(id.toLowerCase(Locale.ROOT));
        if (waiting == null || !waiting.request().conversationId().equals(conversationId)) {
            reply.accept("No approval " + id + " is waiting in this chat. It may have expired or been answered already.");
            return true;
        }
        if (clock.instant().isAfter(waiting.request().expiresAt())) {
            reply.accept("Approval " + id + " has expired; " + waiting.request().toolName() + " was not run.");
            return true;
        }
        ApprovalDecision decision = approve ? ApprovalDecision.ALLOW_ONCE : ApprovalDecision.DENY;
        if (!waiting.request().decisions().contains(decision)) {
            reply.accept("Approval " + id + " can't be answered with /" + command.group(1).toLowerCase(Locale.ROOT) + ".");
            return true;
        }
        // The gate checks the conversation, the argument hash and that the approval is still open, once
        if (!pendingApprovals.decide(conversationId, waiting.request().approvalId(),
                waiting.request().argumentsHash(), decision)) {
            reply.accept("No approval " + id + " is waiting in this chat. It may have expired or been answered already.");
        }
        // Accepted: the outcome message follows when the gate reports the resolution
        return true;
    }

    /**
     * Answers {@code text} in the background, after any earlier turn of the same conversation.
     * Approval requests and their outcomes go to {@code send}, then the answer does.
     *
     * @param send      sends a message to the conversation the text came from
     * @param onFailure told when the turn fails, instead of {@code send} getting an answer
     */
    public void respond(String conversationId, String text, Consumer<String> send, Consumer<RuntimeException> onFailure) {
        CompletableFuture<Void> queued = lastTurn.compute(conversationId, (_, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                        .thenRunAsync(() -> runTurn(conversationId, text, send, onFailure), turnExecutor));
        queued.whenComplete((_, _) -> lastTurn.remove(conversationId, queued));
    }

    private void runTurn(String conversationId, String text, Consumer<String> send, Consumer<RuntimeException> onFailure) {
        try {
            // The channel the turn came from can always be asked; the owner answers there
            String answer = agent.answer(conversationId, text, _ -> true, event -> onEvent(event, send));
            send.accept(answer);
        } catch (RuntimeException e) {
            try {
                onFailure.accept(e);
            } catch (RuntimeException reportFailure) {
                log.warn("Could not report a failed turn in conversation {}", conversationId, reportFailure);
            }
        }
    }

    private void onEvent(AgentEvent event, Consumer<String> send) {
        switch (event) {
            case AgentEvent.ApprovalRequested(ApprovalRequest request) -> {
                Asked waiting = register(request, send);
                try {
                    send.accept(requestMessage(request, waiting.shortId()));
                } catch (RuntimeException e) {
                    // The gate then refuses the call: nobody saw the question
                    asked.remove(waiting.shortId(), waiting);
                    throw e;
                }
            }
            case AgentEvent.ApprovalResolved resolved when resolved.approvalId() != null -> {
                Asked waiting = forget(resolved.approvalId());
                if (waiting != null) {
                    send.accept(outcomeMessage(waiting, resolved.denial()));
                }
            }
            default -> {
                // Tool calls, tokens and refusals nobody was asked about need no message
            }
        }
    }

    private Asked register(ApprovalRequest request, Consumer<String> send) {
        while (true) {
            Asked waiting = new Asked(newShortId(), request, send);
            if (asked.putIfAbsent(waiting.shortId(), waiting) == null) {
                return waiting;
            }
        }
    }

    private Asked forget(String approvalId) {
        for (Asked waiting : asked.values()) {
            if (waiting.request().approvalId().equals(approvalId) && asked.remove(waiting.shortId(), waiting)) {
                return waiting;
            }
        }
        return null;
    }

    private String newShortId() {
        StringBuilder id = new StringBuilder(SHORT_ID_LENGTH);
        for (int i = 0; i < SHORT_ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    /** The same facts as the web chat's approval card, as plain text. */
    String requestMessage(ApprovalRequest request, String shortId) {
        List<String> lines = new ArrayList<>();
        lines.add("JavaClaw wants to run " + request.toolName() + ": " + shown(request.arguments(), "arguments"));
        lines.add("Risk: " + (request.risk() == null ? "UNCLASSIFIED (nobody has classified this tool)" : request.risk().name()));
        if (request.protectedReason() != null) {
            lines.add("Protected: it " + request.protectedReason() + ", so it always needs approval.");
        }
        Map<String, String> details = request.details();
        if (details.containsKey("workingDirectory")) {
            lines.add("Working directory: " + details.get("workingDirectory"));
        }
        if (details.containsKey("subagentType")) {
            lines.add("Subagent: " + details.get("subagentType")
                    + ("true".equals(details.get("runInBackground")) ? " (runs in the background)" : ""));
        }
        if (details.containsKey("prompt")) {
            lines.add("Subagent prompt: " + shown(details.get("prompt"), "prompt"));
        }
        List<String> options = new ArrayList<>();
        if (request.decisions().contains(ApprovalDecision.ALLOW_ONCE)) {
            options.add("/approve " + shortId);
        }
        if (request.decisions().contains(ApprovalDecision.DENY)) {
            options.add("/deny " + shortId);
        }
        lines.add("Reply " + String.join(" or ", options) + " (expires in "
                + describe(Duration.between(clock.instant(), request.expiresAt())) + ").");
        return String.join("\n", lines);
    }

    private static String outcomeMessage(Asked waiting, Denial denial) {
        String tool = waiting.request().toolName();
        if (denial == null) {
            return "Approved " + waiting.shortId() + ": running " + tool + ".";
        }
        return switch (denial.reason()) {
            case USER -> "Denied " + waiting.shortId() + ": " + tool + " was not run.";
            case TIMEOUT -> "Expired " + waiting.shortId() + ": " + tool + " was not run because " + denial.detail() + ".";
            default -> tool + " was not run: " + denial.detail() + ".";
        };
    }

    private static String shown(String text, String what) {
        if (text == null || text.length() <= MAX_SHOWN_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SHOWN_CHARS) + "… (" + what + " truncated: showing "
                + MAX_SHOWN_CHARS + " of " + text.length() + " characters)";
    }

    private static String describe(Duration remaining) {
        long seconds = Math.max(0, Math.round(remaining.toMillis() / 1000.0));
        if (seconds >= 60 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        if (seconds >= 90) {
            return Math.round(seconds / 60.0) + " minutes";
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
