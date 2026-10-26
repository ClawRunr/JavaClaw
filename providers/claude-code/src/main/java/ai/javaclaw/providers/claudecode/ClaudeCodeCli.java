package ai.javaclaw.providers.claudecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The official Claude Code CLI as JavaClaw drives it. JavaClaw never reads, copies or refreshes
 * the CLI's credentials: login, token refresh and the subscription identity stay inside {@code claude}.
 */
public final class ClaudeCodeCli {

    /** Absolute path of the {@code claude} executable when it is not on PATH or in a usual install directory. */
    public static final String COMMAND_ENV = "JAVACLAW_CLAUDE_COMMAND";
    /** A separately logged-in Claude config directory ({@code CLAUDE_CONFIG_DIR=... claude auth login}). */
    public static final String CONFIG_DIR_ENV = "JAVACLAW_CLAUDE_CONFIG_DIR";

    static final String INSTALL_HINT = "Claude Code is not installed (no `claude` on PATH or in the usual install directories). "
            + "Install it with `npm install -g @anthropic-ai/claude-code` or set " + COMMAND_ENV + " to the binary.";
    static final String LOGIN_HINT = "Claude Code is installed but not logged in. Run `claude auth login`, then try again.";
    static final String LOGGED_OUT_HINT = "Claude Code is installed but has no usable login in the environment JavaClaw runs it in. "
            + "Run `claude auth login` as the user JavaClaw runs as, set CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`) "
            + "in JavaClaw's environment, or point " + CONFIG_DIR_ENV + " at a logged-in config directory.";

    // Overrides that would make the CLI bill an API key or another backend instead of the subscription.
    private static final List<String> CONFLICTING_CREDENTIALS = List.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "ANTHROPIC_FOUNDRY_API_KEY");
    private static final List<String> CONFLICTING_BACKENDS = List.of(
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY");
    // Set when JavaClaw itself runs inside a Claude Code session; the child must not join that session.
    private static final List<String> PARENT_SESSION = List.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_CHILD_SESSION",
            "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN",
            "CLAUDE_CODE_EXECPATH", "CLAUDE_PID", "CLAUDE_EFFORT");
    // Never wanted from a child JavaClaw starts: a background self-update, the feedback upload path, and
    // native's own session-title request, which would race the turn for the single admitted upstream request.
    private static final Map<String, String> ALWAYS_QUIET = Map.of(
            "DISABLE_AUTOUPDATER", "1", "DISABLE_FEEDBACK_COMMAND", "1", "CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "1");
    // Searched after PATH: a service launch (launchd, systemd) inherits a bare PATH that carries none of them.
    private static final List<String> HOME_PREFIXES = List.of(
            ".local/bin", ".claude/local", "bin", ".npm-global/bin", ".bun/bin", ".volta/bin");
    private static final List<String> SYSTEM_PREFIXES = List.of("/opt/homebrew/bin", "/usr/local/bin");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final Executor VIRTUAL_THREADS = command -> Thread.ofVirtual().name("claude-code").start(command);

    private ClaudeCodeCli() {
    }

    public record Status(boolean available, boolean loggedIn, String plan, String detail) {
    }

    /** Asks {@code claude auth status} (the local credential store, no Messages request) whether the CLI is ready. */
    public static Status status() {
        Map<String, String> env = new HashMap<>(System.getenv());
        Optional<String> executable = resolve(null, env);
        if (executable.isEmpty()) {
            return new Status(false, false, "", INSTALL_HINT);
        }
        applyConfigDir(env);
        applyLoginIdentity(env);
        env.putAll(ALWAYS_QUIET);
        PARENT_SESSION.forEach(env::remove);
        JsonNode auth = JSON.createObjectNode();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.get(), "auth", "status")
                    .redirectInput(ProcessBuilder.Redirect.from(new File(WINDOWS ? "NUL" : "/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(env);
            process = builder.start();
            InputStream stdout = process.getInputStream();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(stdout), VIRTUAL_THREADS);
            if (process.waitFor(20, TimeUnit.SECONDS)) {
                String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
                if (text.startsWith("{")) {
                    auth = JSON.readTree(text);
                }
            }
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
            // Unreadable answer: reported as logged out below.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        boolean loggedIn = auth.path("loggedIn").isBoolean() && auth.path("loggedIn").booleanValue();
        String plan = auth.path("subscriptionType").isString() ? auth.path("subscriptionType").stringValue() : "";
        return new Status(true, loggedIn, plan, loggedIn ? "" : LOGIN_HINT);
    }

    /**
     * The executable to run: an absolute command as given, else a PATH lookup, else (for the default
     * bare {@code claude} only) the usual install directories.
     */
    static Optional<String> resolve(String command, Map<String, String> env) {
        String head = command != null && !command.isBlank() ? command
                : env.getOrDefault(COMMAND_ENV, "").isBlank() ? "claude" : env.get(COMMAND_ENV);
        if (Path.of(head).isAbsolute()) {
            return Files.isExecutable(Path.of(head)) ? Optional.of(head) : Optional.empty();
        }
        Optional<String> onPath = which(head, env.getOrDefault("PATH", ""));
        if (onPath.isPresent() || !"claude".equals(head)) {
            return onPath;
        }
        String home = env.get(WINDOWS ? "USERPROFILE" : "HOME");
        List<String> prefixes = new ArrayList<>();
        if (home != null) {
            HOME_PREFIXES.forEach(prefix -> prefixes.add(Path.of(home, prefix).toString()));
        }
        if (!WINDOWS) {
            prefixes.addAll(SYSTEM_PREFIXES);
        }
        return which(head, String.join(File.pathSeparator, prefixes));
    }

    /**
     * Copies {@code source} into the environment for one CLI spawn. With an inherited environment,
     * credential and backend overrides are refused rather than letting the CLI bill an API key.
     */
    static Map<String, String> childEnvironment(Map<String, String> source, boolean inherited) {
        Map<String, String> env = new HashMap<>(source);
        if (inherited) {
            List<String> conflicts = new ArrayList<>();
            CONFLICTING_CREDENTIALS.stream().filter(key -> !env.getOrDefault(key, "").isEmpty()).forEach(conflicts::add);
            CONFLICTING_BACKENDS.stream().filter(key -> isTruthy(env.get(key))).forEach(conflicts::add);
            if (!conflicts.isEmpty()) {
                throw new IllegalStateException("The Claude subscription provider refuses conflicting Claude Code "
                        + "auth/backend overrides in JavaClaw's environment: " + String.join(", ", conflicts));
            }
        }
        PARENT_SESSION.forEach(env::remove);
        applyLoginIdentity(env);
        applyConfigDir(env);
        env.putAll(ALWAYS_QUIET);
        return env;
    }

    static Process start(List<String> command, Map<String, String> env, Path workdir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(env);
        if (workdir != null) {
            builder.directory(workdir.toFile());
        }
        return builder.start();
    }

    /**
     * Claude Code's local login check reads the process identity: without {@code USER} a logged-in
     * CLI reports {@code loggedIn: false} instantly, which a launchd/systemd environment can trigger.
     */
    private static void applyLoginIdentity(Map<String, String> env) {
        String name = System.getProperty("user.name", "");
        if (name.isEmpty()) {
            return;
        }
        if (WINDOWS) {
            env.putIfAbsent("USERNAME", name);
        } else {
            env.putIfAbsent("LOGNAME", env.getOrDefault("USER", name));
        }
        env.putIfAbsent("USER", name);
    }

    private static void applyConfigDir(Map<String, String> env) {
        String override = env.remove(CONFIG_DIR_ENV);
        if (override != null && !override.isBlank()) {
            String dir = override.trim();
            if (dir.equals("~") || dir.startsWith("~/")) {
                dir = System.getProperty("user.home") + dir.substring(1);
            }
            env.put("CLAUDE_CONFIG_DIR", dir);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isTruthy(String value) {
        return value != null && !List.of("", "0", "false", "no", "off").contains(value.toLowerCase());
    }

    private static Optional<String> which(String name, String path) {
        List<String> names = WINDOWS && !name.contains(".") ? List.of(name + ".exe", name + ".cmd", name) : List.of(name);
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : names) {
                Path file = Path.of(dir, candidate);
                if (Files.isRegularFile(file) && Files.isExecutable(file)) {
                    return Optional.of(file.toString());
                }
            }
        }
        return Optional.empty();
    }
}
