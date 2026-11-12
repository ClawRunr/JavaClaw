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
 * {@code /approve_<id>} or {@code /deny_<id>} (or {@code /approve <id>}) is answered here and never
 * reaches the model; anything else goes to {@link #respond}, which runs the agent turn in the
 * background. When the turn needs approval, the chat gets a request written by
 * {@link ApprovalMessages} with a 6-character id, and the turn waits for the owner to answer. Once
 * it is answered or expires, the outcome is sent as a short reply.
 *
 * <p>Turns run off the channel's receiving thread on purpose: every channel takes its messages one
 * at a time, so a turn waiting on that thread could never receive the command it waits for. Turns
 * of one conversation still run one at a time, in order.
 *
 * <p>The approval itself is the gate's: the short id only names a pending approval, which is
 * decided through {@link PendingApprovals#decide} with its conversation and argument hash, once.
 */
@Component
public class ChannelApprovals {

    private static final Logger log = LoggerFactory.getLogger(ChannelApprovals.class);

    /**
     * {@code /approve_a1b2c3} (one tap on Telegram), or {@code /approve a1b2c3}; Telegram may add the
     * bot's name to a command.
     */
    private static final Pattern COMMAND = Pattern.compile(
            "^\\s*/(approve|deny)(?:_([a-z0-9]+))?(?:@\\S+)?(?:\\s+(\\S+))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    static final int SHORT_ID_LENGTH = 6;

    private final Agent agent;
    private final PendingApprovals pendingApprovals;
    private final ApprovalMessages messages;
    private final Clock clock;
    private final Executor turnExecutor;
    private final SecureRandom random = new SecureRandom();
    /** Waiting approvals by short id. */
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();
    /** The last queued turn of each conversation, so the next one runs after it. */
    private final Map<String, CompletableFuture<Void>> lastTurn = new ConcurrentHashMap<>();

    /** A request the owner was asked about, and the chat it was asked in. */
    private record Asked(String shortId, ApprovalRequest request, ApprovalChat chat) {
    }

    @Autowired
    public ChannelApprovals(Agent agent, PendingApprovals pendingApprovals) {
        this(agent, pendingApprovals, new ApprovalMessages(Clock.systemDefaultZone()), Clock.systemDefaultZone(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("channel-turn-", 0).factory()));
    }

    /** @param turnExecutor runs agent turns; it must not be the thread messages arrive on */
    public ChannelApprovals(Agent agent, PendingApprovals pendingApprovals, ApprovalMessages messages, Clock clock,
                            Executor turnExecutor) {
        this.agent = agent;
        this.pendingApprovals = pendingApprovals;
        this.messages = messages;
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
     * Answers an approval command from the channel's allowed user. Call it before {@link #respond},
     * on the thread the message arrived on.
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
        String verb = command.group(1).toLowerCase(Locale.ROOT);
        String id = command.group(2) != null ? command.group(2) : command.group(3);
        if (id == null) {
            reply.accept("Reply /" + verb + "_<id> with the id from the approval request.");
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
            reply.accept("Approval " + id + " can't be answered with /" + verb + ".");
            return true;
        }
        // The gate checks the conversation, the argument hash and that the approval is still open, once
        if (!pendingApprovals.decide(conversationId, waiting.request().approvalId(),
                waiting.request().argumentsHash(), decision)) {
            reply.accept("No approval " + id + " is waiting in this chat. It may have expired or been answered already.");
        }
        // Accepted: the outcome follows when the gate reports the resolution
        return true;
    }

    /**
     * Answers {@code text} in the background, after any earlier turn of the same conversation.
     * Approval requests and their outcomes go to {@code chat}, then the answer does.
     *
     * @param onFailure told when the turn fails, instead of {@code chat} getting an answer
     */
    public void respond(String conversationId, String text, ApprovalChat chat, Consumer<RuntimeException> onFailure) {
        CompletableFuture<Void> queued = lastTurn.compute(conversationId, (_, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                        .thenRunAsync(() -> runTurn(conversationId, text, chat, onFailure), turnExecutor));
        queued.whenComplete((_, _) -> lastTurn.remove(conversationId, queued));
    }

    private void runTurn(String conversationId, String text, ApprovalChat chat, Consumer<RuntimeException> onFailure) {
        try {
            // The owner answers in the chat the turn came from, unless others can read it
            String answer = agent.answer(conversationId, text, _ -> chat.canAsk(), event -> onEvent(event, chat));
            chat.send(answer);
        } catch (RuntimeException e) {
            try {
                onFailure.accept(e);
            } catch (RuntimeException reportFailure) {
                log.warn("Could not report a failed turn in conversation {}", conversationId, reportFailure);
            }
        }
    }

    private void onEvent(AgentEvent event, ApprovalChat chat) {
        switch (event) {
            case AgentEvent.ApprovalRequested(ApprovalRequest request) -> ask(request, chat);
            case AgentEvent.ApprovalResolved resolved when resolved.approvalId() != null -> {
                Asked waiting = forget(resolved.approvalId());
                if (waiting != null) {
                    tellOutcome(waiting, resolved.denial());
                }
            }
            default -> {
                // Tool calls, tokens and refusals nobody was asked about need no message
            }
        }
    }

    private void ask(ApprovalRequest request, ApprovalChat chat) {
        // Registered before it is sent, so an answer arriving right away finds it
        Asked waiting;
        do {
            waiting = new Asked(newShortId(), request, chat);
        } while (asked.putIfAbsent(waiting.shortId(), waiting) != null);
        try {
            chat.sendFormatted(messages.request(request, waiting.shortId(), chat.format()));
        } catch (RuntimeException e) {
            // The gate then refuses the call: nobody saw the question
            asked.remove(waiting.shortId(), waiting);
            throw e;
        }
    }

    private void tellOutcome(Asked waiting, Denial denial) {
        ApprovalChat chat = waiting.chat();
        try {
            chat.sendFormatted(messages.outcome(waiting.request(), waiting.shortId(), denial, chat.format()));
        } catch (RuntimeException e) {
            log.warn("Could not tell the outcome of approval {}", waiting.shortId(), e);
        }
    }

    private String newShortId() {
        StringBuilder id = new StringBuilder(SHORT_ID_LENGTH);
        for (int i = 0; i < SHORT_ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private Asked forget(String approvalId) {
        for (Asked waiting : asked.values()) {
            if (waiting.request().approvalId().equals(approvalId) && asked.remove(waiting.shortId(), waiting)) {
                return waiting;
            }
        }
        return null;
    }
}
