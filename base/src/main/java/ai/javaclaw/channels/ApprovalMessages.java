package ai.javaclaw.channels;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.mcp.McpToolDirectory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Writes approval requests for messaging channels: a plain-language title per tool, the facts that
 * matter for that tool instead of raw JSON, warnings on their own lines, the expiry as a clock time
 * and the commands to answer with. {@link ApprovalFormat} turns it into the channel's markup.
 *
 * <p>Presentation only: what is asked, and how the answer is checked, stays with the gate.
 */
public class ApprovalMessages {

    static final int PREVIEW_LINES = 6;
    static final int PREVIEW_CHARS = 300;
    private static final long LARGEST_FILE_READ = 10L * 1024 * 1024;
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Titles for tools that need no special details; anything else is "run &lt;tool&gt;". */
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("Write", "write a file"),
            Map.entry("Edit", "edit a file"),
            Map.entry("WebFetch", "fetch a web page"),
            Map.entry("WebSearch", "search the web"),
            Map.entry("Task", "start a subagent"),
            Map.entry("addStdioMcpServer", "add an MCP server"),
            Map.entry("addStreamableHttpMcpServer", "add an MCP server"),
            Map.entry("Bash", "run a shell command"),
            Map.entry("KillShell", "stop a shell command"),
            Map.entry("createTask", "create a task"),
            Map.entry("scheduleTask", "schedule a task"),
            Map.entry("scheduleRecurringTask", "schedule a recurring task"),
            Map.entry("deleteRecurringTask", "delete a recurring task"),
            Map.entry("MemoryCreate", "change the agent's memory"),
            Map.entry("MemoryStrReplace", "change the agent's memory"),
            Map.entry("MemoryInsert", "change the agent's memory"),
            Map.entry("MemoryDelete", "change the agent's memory"),
            Map.entry("MemoryRename", "change the agent's memory"),
            Map.entry("navigateTo", "open a web page in the browser"),
            Map.entry("clickElement", "click on a web page"),
            Map.entry("fillInput", "type into a web page"),
            Map.entry("evaluateJavaScript", "run JavaScript in the browser"));

    /** One line, block or warning of a message, before it is put in a channel's markup. */
    sealed interface Part {
    }

    record Field(String label, String value, boolean monospace) implements Part {
    }

    record Block(String label, Preview preview) implements Part {
    }

    record Warning(String text) implements Part {
    }

    /** What a request is about, independent of any channel. */
    record Summary(String title, List<Part> parts) {
    }

    /** Text cut to fit a message, and what was left out ("(+12 more lines)"), if anything. */
    record Preview(String shown, String more) {
    }

    private final Path workspace;
    private final Function<String, Optional<McpToolDirectory.McpTool>> mcpTools;
    private final Clock clock;

    /**
     * @param workspace the agent's workspace; paths in it are shown as {@code workspace/...}
     * @param mcpTools  finds the MCP server a tool comes from
     * @param clock     its zone is the one expiry times are shown in
     */
    public ApprovalMessages(Path workspace, Function<String, Optional<McpToolDirectory.McpTool>> mcpTools, Clock clock) {
        this.workspace = realPath(workspace);
        this.mcpTools = mcpTools;
        this.clock = clock;
    }

    /** The request, ending with the expiry and the commands to answer with. */
    public String request(ApprovalRequest request, String shortId, ApprovalFormat format) {
        List<String> lines = body(summarize(request), format);
        lines.add(format.text("Expires at " + CLOCK_TIME.format(request.expiresAt().atZone(clock.getZone()))));
        List<String> commands = new ArrayList<>();
        if (request.decisions().contains(ApprovalDecision.ALLOW_ONCE)) {
            commands.add("/approve_" + shortId);
        }
        if (request.decisions().contains(ApprovalDecision.DENY)) {
            commands.add("/deny_" + shortId);
        }
        lines.add(format.text("Reply ") + String.join(format.text(" or "), commands.stream().map(format::command).toList()));
        return String.join(format.lineBreak(), lines);
    }

    /** The request as it was asked, with the outcome where the commands were; for editing the request in place. */
    public String resolvedRequest(ApprovalRequest request, Denial denial, ApprovalFormat format) {
        List<String> lines = body(summarize(request), format);
        lines.add(outcome(request.toolName(), denial, format));
        return String.join(format.lineBreak(), lines);
    }

    /** A short reply with the outcome, for channels that cannot edit the request. */
    public String outcomeReply(ApprovalRequest request, String shortId, Denial denial, ApprovalFormat format) {
        return outcome(request.toolName(), denial, format) + format.text(" (" + shortId + ")");
    }

    private static String outcome(String toolName, Denial denial, ApprovalFormat format) {
        if (denial == null) {
            return "✅ " + format.bold("Approved") + format.text(": running " + toolName + ".");
        }
        return switch (denial.reason()) {
            case USER -> "❌ " + format.bold("Denied") + format.text(": " + toolName + " was not run.");
            case TIMEOUT -> "⌛ " + format.bold("Expired") + format.text(": " + toolName + " was not run because "
                    + denial.detail() + ".");
            default -> "⛔ " + format.bold("Not run") + format.text(": " + toolName + " — " + denial.detail() + ".");
        };
    }

    private static List<String> body(Summary summary, ApprovalFormat format) {
        List<String> lines = new ArrayList<>();
        lines.add(format.bold("Approval needed: " + summary.title()));
        List<String> warnings = new ArrayList<>();
        for (Part part : summary.parts()) {
            switch (part) {
                case Field(String label, String value, boolean monospace) ->
                        lines.add(format.text(label + ": ") + (monospace ? format.code(value) : format.text(value)));
                case Block(String label, Preview preview) -> {
                    lines.add(format.text(label + ":"));
                    lines.add(format.block(preview.shown()));
                    if (preview.more() != null) {
                        lines.add(format.text(preview.more()));
                    }
                }
                case Warning(String text) -> warnings.add("⚠️ " + format.text(text));
            }
        }
        lines.addAll(warnings);
        return lines;
    }

    // -----------------------------------------------------------------------
    // What each tool is about
    // -----------------------------------------------------------------------

    Summary summarize(ApprovalRequest request) {
        String tool = request.toolName();
        Map<String, Object> args = parse(request.arguments());
        List<Part> parts = new ArrayList<>();
        String title = TITLES.getOrDefault(tool, "run " + tool);
        switch (tool) {
            case "Write" -> write(args, parts);
            case "Edit" -> edit(args, parts);
            case "WebFetch" -> parts.add(new Field("URL", string(args, "url"), true));
            case "WebSearch" -> parts.add(new Field("Query", string(args, "query"), true));
            case "Task" -> {
                parts.add(new Field("Subagent", string(args, "subagent_type"), true));
                if (Boolean.TRUE.equals(args.get("run_in_background"))) {
                    parts.add(new Field("Runs", "in the background", false));
                }
                parts.add(new Block("Prompt", preview(string(args, "prompt"))));
            }
            case "Bash" -> parts.add(new Block("Command", preview(string(args, "command"))));
            default -> {
                Optional<McpToolDirectory.McpTool> mcp = request.risk() == null ? mcpTools.apply(tool) : Optional.empty();
                if (mcp.isPresent()) {
                    title = "use an MCP tool";
                    parts.add(new Field("Server", mcp.get().server(), true));
                    parts.add(new Field("Tool", mcp.get().tool(), true));
                }
                parts.add(arguments(request.arguments(), args));
            }
        }
        if (request.risk() != null) {
            parts.add(new Field("Risk", request.risk().name(), false));
        } else {
            parts.add(new Field("Risk", "unclassified (nobody has classified this tool)", false));
        }
        if (request.protectedReason() != null) {
            parts.add(new Warning("Protected: it " + request.protectedReason() + ", so it always needs approval"));
        }
        return new Summary(title, parts);
    }

    private void write(Map<String, Object> args, List<Part> parts) {
        String content = string(args, "content");
        Location location = path(args, parts);
        long after = content.getBytes(StandardCharsets.UTF_8).length;
        Optional<Long> before = location == null ? Optional.empty() : size(location.path());
        if (before.isPresent()) {
            parts.add(new Field("Size", human(before.get()) + " → " + human(after), false));
            parts.add(new Warning("Overwrites an existing file"));
        } else {
            parts.add(new Field("Size", "new file, " + human(after), false));
        }
        parts.add(new Block("Content", preview(content)));
    }

    private void edit(Map<String, Object> args, List<Part> parts) {
        String oldText = string(args, "old_string");
        String newText = string(args, "new_string");
        boolean all = Boolean.TRUE.equals(args.get("replace_all"));
        Location location = path(args, parts);
        Optional<Long> before = location == null ? Optional.empty() : size(location.path());
        if (before.isEmpty()) {
            parts.add(new Field("Size", "the file does not exist", false));
        } else {
            Optional<String> current = before.get() <= LARGEST_FILE_READ ? read(location.path()) : Optional.empty();
            if (current.isEmpty()) {
                parts.add(new Field("Size", human(before.get()), false));
            } else if (oldText.isEmpty() || !current.get().contains(oldText)) {
                parts.add(new Field("Size", human(before.get()) + " (the text to replace is not in the file)", false));
            } else {
                String changed = all ? current.get().replace(oldText, newText)
                        : current.get().replaceFirst(java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText));
                parts.add(new Field("Size", human(before.get()) + " → "
                        + human(changed.getBytes(StandardCharsets.UTF_8).length), false));
            }
        }
        if (all) {
            parts.add(new Field("Replaces", "every occurrence", false));
        }
        parts.add(new Block("Old text", preview(oldText)));
        parts.add(new Block("New text", preview(newText)));
    }

    /** Adds the path line (and a warning when it is outside the workspace). */
    private Location path(Map<String, Object> args, List<Part> parts) {
        String raw = args.get("filePath") instanceof String s ? s : string(args, "file_path");
        Location location = locate(raw);
        if (location == null) {
            parts.add(new Field("Path", raw, true));
            parts.add(new Warning("The path could not be read"));
            return null;
        }
        parts.add(new Field("Path", location.shown(), true));
        if (!location.inWorkspace()) {
            parts.add(new Warning("Outside the workspace"));
        }
        return location;
    }

    record Location(Path path, String shown, boolean inWorkspace) {
    }

    /** Relative paths are resolved the way the file tools resolve them: against the working directory. */
    Location locate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            return null;
        }
        Path absolute = (path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(path))
                .toAbsolutePath().normalize();
        Path real = realPath(absolute);
        if (real.startsWith(workspace)) {
            String relative = workspace.relativize(real).toString().replace('\\', '/');
            return new Location(absolute, relative.isEmpty() ? "workspace/" : "workspace/" + relative, true);
        }
        return new Location(absolute, absolute.toString(), false);
    }

    /** Arguments as indented {@code key: value} lines; anything that is not a JSON object as it came. */
    private static Block arguments(String raw, Map<String, Object> args) {
        if (args.isEmpty()) {
            return new Block("Arguments", preview(raw == null || raw.isBlank() || raw.equals("{}") ? "(none)" : raw));
        }
        List<String> lines = new ArrayList<>();
        args.forEach((key, value) -> {
            String text = value instanceof String s ? s : JSON.writeValueAsString(value);
            String[] valueLines = text.split("\n", -1);
            lines.add("  " + key + ": " + valueLines[0]);
            for (int i = 1; i < valueLines.length; i++) {
                lines.add("    " + valueLines[i]);
            }
        });
        return new Block("Arguments", preview(String.join("\n", lines)));
    }

    // -----------------------------------------------------------------------

    /** At most {@value #PREVIEW_LINES} lines or {@value #PREVIEW_CHARS} characters, and what was left out. */
    static Preview preview(String text) {
        if (text == null || text.isEmpty()) {
            return new Preview("(empty)", null);
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] lines = body.split("\n", -1);
        StringBuilder shown = new StringBuilder();
        int chars = 0;
        int linesShown = 0;
        boolean cutInLine = false;
        for (String line : lines) {
            if (linesShown == PREVIEW_LINES) {
                break;
            }
            int room = PREVIEW_CHARS - chars;
            if (linesShown > 0) {
                shown.append('\n');
            }
            if (line.length() > room) {
                shown.append(line, 0, Math.max(0, room)).append('…');
                chars += Math.max(0, room);
                linesShown++;
                cutInLine = true;
                break;
            }
            shown.append(line);
            chars += line.length();
            linesShown++;
        }
        int moreLines = lines.length - linesShown;
        String more = moreLines > 0 ? "(+" + moreLines + (moreLines == 1 ? " more line)" : " more lines)")
                : cutInLine ? "(+" + (body.length() - chars - (linesShown - 1)) + " more characters)" : null;
        return new Preview(shown.toString(), more);
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static Optional<Long> size(Path path) {
        try {
            return Files.isRegularFile(path) ? Optional.of(Files.size(path)) : Optional.empty();
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> read(Path path) {
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException | SecurityException | java.io.UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : value instanceof String s ? s : JSON.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Object value = JSON.readValue(arguments, Object.class);
            return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    /** Absolute and normalized, with symlinks resolved as far as the path exists. */
    private static Path realPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException | SecurityException e) {
            return absolute;
        }
    }
}
