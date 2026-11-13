package ai.javaclaw.channels;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
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
 * {@code /approve_<id>} or {@code /deny_<id>} (or {@code /approve <id>}) is answered here and never
 * reaches the model; anything else goes to {@link #respond}. When a turn pauses for approval, the
 * chat gets the turn's text so far, then one request per call, written by {@link ApprovalMessages}
 * with a 6-character id. Answering the last of them resumes the turn, and its answer follows.
 *
 * <p>Turns and resumptions run in the background, one at a time per conversation and in order, so
 * a channel's receiving thread is never held by the agent.
 *
 * <p>The short id only names a request; the agent checks the conversation, the argument hash and
 * that the request still waits, once.
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
    private final ApprovalMessages messages;
    private final Executor turnExecutor;
    private final SecureRandom random = new SecureRandom();
    /** Waiting approvals by short id. */
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();
    /** The last queued turn of each conversation, so the next one runs after it. */
    private final Map<String, CompletableFuture<Void>> lastTurn = new ConcurrentHashMap<>();

    /** A request the owner was asked about, the chat it was asked in, and who hears of a failed resumption. */
    private record Asked(String shortId, ApprovalRequest request, ApprovalChat chat,
                         Consumer<RuntimeException> onFailure) {
    }

    @Autowired
    public ChannelApprovals(Agent agent) {
        this(agent, new ApprovalMessages(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("channel-turn-", 0).factory()));
    }

    /** @param turnExecutor runs agent turns; it must not be the thread messages arrive on */
    public ChannelApprovals(Agent agent, ApprovalMessages messages, Executor turnExecutor) {
        this.agent = agent;
        this.messages = messages;
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
            reply.accept(notWaiting(id));
            return true;
        }
        ApprovalDecision decision = approve ? ApprovalDecision.ALLOW_ONCE : ApprovalDecision.DENY;
        if (!waiting.request().decisions().contains(decision)) {
            reply.accept("Approval " + id + " can't be answered with /" + verb + ".");
            return true;
        }
        // The agent checks the conversation, the argument hash and that the request still waits, once
        enqueue(conversationId, () -> decide(conversationId, id, waiting, decision, reply));
        return true;
    }

    /**
     * Answers {@code text} in the background, after any earlier turn of the same conversation.
     * The answer goes to {@code chat}, followed by any approval requests the turn paused on.
     *
     * @param onFailure told when the turn fails, instead of {@code chat} getting an answer
     */
    public void respond(String conversationId, String text, ApprovalChat chat, Consumer<RuntimeException> onFailure) {
        enqueue(conversationId, () -> runTurn(conversationId, text, chat, onFailure));
    }

    private void enqueue(String conversationId, Runnable work) {
        CompletableFuture<Void> queued = lastTurn.compute(conversationId, (_, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                        .thenRunAsync(work, turnExecutor));
        queued.whenComplete((_, _) -> lastTurn.remove(conversationId, queued));
    }

    private void runTurn(String conversationId, String text, ApprovalChat chat, Consumer<RuntimeException> onFailure) {
        Turn turn = new Turn(chat, onFailure);
        try {
            // The owner answers in the chat the turn came from, unless others can read it
            String answer = agent.answer(conversationId, text, _ -> chat.canAsk(), turn::onEvent);
            turn.finish(answer);
        } catch (RuntimeException e) {
            report(conversationId, onFailure, e);
        }
    }

    private void decide(String conversationId, String id, Asked waiting, ApprovalDecision decision,
                        Consumer<String> reply) {
        Turn turn = new Turn(waiting.chat(), waiting.onFailure());
        Agent.DecisionResult result;
        try {
            result = agent.decide(conversationId, waiting.request().approvalId(), waiting.request().argumentsHash(),
                    decision, _ -> waiting.chat().canAsk(), turn::onEvent);
        } catch (RuntimeException e) {
            report(conversationId, waiting.onFailure(), e);
            return;
        }
        switch (result) {
            case IGNORED -> reply.accept(notWaiting(id));
            case RECORDED -> {
                // Other requests of the same reply still wait; their answers resume the turn
            }
            case RESUMED -> {
                if (turn.failure != null) {
                    report(conversationId, waiting.onFailure(), new IllegalStateException(turn.failure));
                } else {
                    turn.finish(turn.text.toString());
                }
            }
        }
    }

    private static String notWaiting(String id) {
        return "No approval " + id + " is waiting in this chat. It may have been answered already.";
    }

    private static void report(String conversationId, Consumer<RuntimeException> onFailure, RuntimeException e) {
        try {
            onFailure.accept(e);
        } catch (RuntimeException reportFailure) {
            log.warn("Could not report a failed turn in conversation {}", conversationId, reportFailure);
        }
    }

    /** What one turn, or one resumption, sends to the chat. */
    private final class Turn {

        private final ApprovalChat chat;
        private final Consumer<RuntimeException> onFailure;
        private final List<ApprovalRequest> requests = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String failure;

        Turn(ApprovalChat chat, Consumer<RuntimeException> onFailure) {
            this.chat = chat;
            this.onFailure = onFailure;
        }

        void onEvent(AgentEvent event) {
            switch (event) {
                case AgentEvent.Token(String token) -> text.append(token);
                case AgentEvent.ApprovalRequested(ApprovalRequest request) -> requests.add(request);
                case AgentEvent.ApprovalResolved resolved when resolved.approvalId() != null -> {
                    Asked waiting = forget(resolved.approvalId());
                    if (waiting != null) {
                        tellOutcome(waiting, resolved.denial());
                    }
                }
                case AgentEvent.Failed(String message) -> failure = message;
                default -> {
                    // Tool calls and refusals nobody was asked about need no message
                }
            }
        }

        /** The answer, then the requests it paused on, so the chat reads in order. */
        void finish(String answer) {
            if (answer != null && !answer.isBlank()) {
                chat.send(answer);
            }
            requests.forEach(request -> ask(request, chat, onFailure));
        }
    }

    private void ask(ApprovalRequest request, ApprovalChat chat, Consumer<RuntimeException> onFailure) {
        // Registered before it is sent, so an answer arriving right away finds it
        Asked waiting;
        do {
            waiting = new Asked(newShortId(), request, chat, onFailure);
        } while (asked.putIfAbsent(waiting.shortId(), waiting) != null);
        try {
            chat.sendFormatted(messages.request(request, waiting.shortId(), chat.format()));
        } catch (RuntimeException e) {
            // Nobody saw it; the owner's next message moves on and denies it
            asked.remove(waiting.shortId(), waiting);
            log.warn("Could not send approval request for {}", request.toolName(), e);
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
