package ai.javaclaw.channels;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.javaclaw.utils.JsonObjects.parseObject;

/**
 * Writes approval requests for messaging channels: the tool, its arguments as {@code key: value}
 * lines, why it always asks if it touches a protected path, the expiry as a clock time and the
 * commands to answer with. {@link ApprovalFormat} turns it into the channel's markup.
 *
 * <p>Every message fits the channel's {@link ApprovalFormat#maxLength()}: values are cut at
 * {@value #VALUE_LIMIT} characters and arguments that still do not fit are left out. Whenever
 * anything is cut, the message says so and points to the web chat, which shows everything.
 *
 * <p>Presentation only: what is asked, and how the answer is checked, stays with the gate.
 */
public class ApprovalMessages {

    static final int VALUE_LIMIT = 300;
    static final String NOT_SHOWN_IN_FULL = "Not shown in full. Approve from the web chat to see everything.";
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock;

    /** @param clock its zone is the one expiry times are shown in */
    public ApprovalMessages(Clock clock) {
        this.clock = clock;
    }

    /** The request, ending with the expiry and the commands to answer with. */
    public String request(ApprovalRequest request, String shortId, ApprovalFormat format) {
        List<String> commands = new ArrayList<>();
        if (request.decisions().contains(ApprovalDecision.ALLOW_ONCE)) {
            commands.add("/approve_" + shortId);
        }
        if (request.decisions().contains(ApprovalDecision.DENY)) {
            commands.add("/deny_" + shortId);
        }
        return fit(request, format, List.of(
                format.text("Expires at " + CLOCK_TIME.format(request.expiresAt().atZone(clock.getZone()))),
                format.text("Reply ") + String.join(format.text(" or "), commands.stream().map(format::command).toList())));
    }

    /** The request as it was asked, with the outcome where the commands were; for editing the request in place. */
    public String resolvedRequest(ApprovalRequest request, Denial denial, ApprovalFormat format) {
        return fit(request, format, List.of(outcome(request.toolName(), denial, format)));
    }

    /** A short reply with the outcome, for channels that cannot edit the request. */
    public String outcomeReply(ApprovalRequest request, String shortId, Denial denial, ApprovalFormat format) {
        return outcome(request.toolName(), denial, format) + format.text(" (" + shortId + ")");
    }

    private static String outcome(String toolName, Denial denial, ApprovalFormat format) {
        String tool = clip(toolName);
        if (denial == null) {
            return "✅ " + format.bold("Approved") + format.text(": running " + tool + ".");
        }
        String detail = clip(denial.detail());
        return switch (denial.reason()) {
            case USER -> "❌ " + format.bold("Denied") + format.text(": " + tool + " was not run.");
            case TIMEOUT -> "⌛ " + format.bold("Expired") + format.text(": " + tool + " was not run because "
                    + detail + ".");
            default -> "⛔ " + format.bold("Not run") + format.text(": " + tool + " — " + detail + ".");
        };
    }

    /** The message with as many arguments as fit before {@code tail}. */
    private static String fit(ApprovalRequest request, ApprovalFormat format, List<String> tail) {
        String tool = clip(request.toolName());
        Arguments arguments = arguments(request.arguments());
        boolean clipped = arguments.cut() || isLong(request.toolName());
        List<String> entries = arguments.entries();
        String message = null;
        for (int shown = entries.size(); shown >= 0; shown--) {
            message = compose(tool, entries.subList(0, shown), clipped || shown < entries.size(),
                    request.protectedReason(), tail, format);
            if (message.length() <= format.maxLength()) {
                break;
            }
        }
        return message;
    }

    private static String compose(String tool, List<String> arguments, boolean cut, String protectedReason,
                                  List<String> tail, ApprovalFormat format) {
        List<String> lines = new ArrayList<>();
        lines.add(format.bold("JavaClaw wants to run") + format.text(" ") + format.code(tool));
        if (!arguments.isEmpty()) {
            lines.add(format.block(String.join("\n", arguments)));
        }
        if (cut) {
            lines.add(format.text(NOT_SHOWN_IN_FULL));
        }
        if (protectedReason != null) {
            lines.add("⚠️ " + format.text("Protected: it " + clip(protectedReason) + ", so it always needs approval"));
        }
        lines.addAll(tail);
        return String.join(format.lineBreak(), lines);
    }

    /** The argument lines, and whether any value in them was cut. */
    record Arguments(List<String> entries, boolean cut) {
    }

    /**
     * One entry per argument, as indented {@code key: value} lines with each value cut at
     * {@value #VALUE_LIMIT} characters; arguments that are not a JSON object as one cut entry.
     */
    static Arguments arguments(String raw) {
        Map<String, Object> args = parseObject(raw);
        if (args.isEmpty()) {
            boolean none = raw == null || raw.isBlank() || raw.strip().equals("{}");
            return new Arguments(List.of("  " + (none ? "(none)" : clip(raw))), !none && isLong(raw));
        }
        List<String> entries = new ArrayList<>();
        boolean cut = false;
        for (Map.Entry<String, Object> arg : args.entrySet()) {
            String text = arg.getValue() instanceof String s ? s : JSON.writeValueAsString(arg.getValue());
            cut |= isLong(arg.getKey()) || isLong(text);
            entries.add("  " + clip(arg.getKey()) + ": " + clip(text).replace("\n", "\n    "));
        }
        return new Arguments(entries, cut);
    }

    private static boolean isLong(String text) {
        return text != null && text.length() > VALUE_LIMIT;
    }

    /** At most {@value #VALUE_LIMIT} characters, ending in "…" when cut. */
    static String clip(String text) {
        if (text == null || text.length() <= VALUE_LIMIT) {
            return text == null ? "" : text;
        }
        int end = Character.isHighSurrogate(text.charAt(VALUE_LIMIT - 1)) ? VALUE_LIMIT - 1 : VALUE_LIMIT;
        return text.substring(0, end) + "…";
    }
}
