package ai.javaclaw.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static ai.javaclaw.utils.FilePaths.realPath;

/**
 * Paths a tool call always asks about, whatever {@code agent.approval.allow} says: what keeps the
 * gate in place (the configuration) and what the agent is told and remembers (its instructions,
 * skills, subagents and conversation history).
 *
 * <p>Tool and argument names play no part. Every string value in a call's arguments, at any depth,
 * is checked twice:
 * <ul>
 *   <li>as a path: relative values resolve against the working directory, as the file tools
 *       resolve them, with {@code ..} and symlinks resolved, so {@code workspace/tmp/../skills/x}
 *       is caught;</li>
 *   <li>as text: a value that mentions a protected file name or directory, such as a shell command
 *       naming {@code application.yaml}.</li>
 * </ul>
 * The text check is a tripwire for mistakes and casual prompt injection, not a sandbox: a shell
 * command can still build a file name at run time.
 *
 * <p>Entries of {@code agent.approval.protected-paths}:
 * <ul>
 *   <li>a name without {@code /}, such as {@code application*.yaml}: that file name anywhere, where
 *       {@code *} stands for any run of letters, digits, {@code .}, {@code _} and {@code -};</li>
 *   <li>a path ending in {@code /}: everything in that directory;</li>
 *   <li>any other path: that file.</li>
 * </ul>
 * A path starting with {@code workspace/} is inside the configured {@code agent.workspace}; other
 * relative paths are relative to the working directory.
 */
public class ProtectedPaths {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WORKSPACE_PREFIX = "workspace/";
    /** Longer strings are not paths; they are still checked as text. */
    private static final int LONGEST_PATH = 4096;
    /** Characters that belong to a file name; a mention must not be part of a longer name. */
    private static final String NAME_CHAR = "[\\w.-]";

    /**
     * One protected entry.
     *
     * @param shown     how the reason names it
     * @param path      the protected file or directory, or {@code null} for a file-name pattern
     * @param directory whether everything below {@code path} is protected
     * @param fileName  the file-name pattern, for a name entry
     * @param mention   finds the entry mentioned in text
     */
    private record Entry(String shown, Path path, boolean directory, Pattern fileName, Pattern mention) {
    }

    private final Path workingDirectory;
    private final List<Entry> entries;

    /**
     * @param configFile     the runtime configuration file, always protected
     * @param protectedPaths the configured entries
     * @param workspace      the agent's workspace, where {@code workspace/...} entries point
     */
    public ProtectedPaths(Path configFile, List<String> protectedPaths, Path workspace) {
        this(configFile, protectedPaths, workspace, Path.of(System.getProperty("user.dir")));
    }

    ProtectedPaths(Path configFile, List<String> protectedPaths, Path workspace, Path workingDirectory) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        List<Entry> all = new ArrayList<>();
        all.add(pathEntry(configFile.toString(), this.workingDirectory.resolve(configFile), false));
        for (String raw : protectedPaths) {
            all.add(entry(raw, workspace));
        }
        this.entries = List.copyOf(all);
    }

    /** Why the call always asks, or empty when none of its argument values touches a protected path. */
    public Optional<String> reason(String arguments) {
        for (String value : strings(arguments)) {
            Optional<Path> path = asPath(value);
            for (Entry entry : entries) {
                if (path.map(p -> matches(entry, p)).orElse(false) || entry.mention().matcher(value).find()) {
                    return Optional.of("touches a protected path (" + entry.shown() + ")");
                }
            }
        }
        return Optional.empty();
    }

    private Entry entry(String raw, Path workspace) {
        boolean directory = raw.endsWith("/");
        String trimmed = raw.replaceAll("/+$", "");
        if (!directory && !trimmed.contains("/")) {
            String name = glob(trimmed);
            return new Entry(raw, null, false, Pattern.compile(name, Pattern.CASE_INSENSITIVE), mention(name));
        }
        Path path = trimmed.startsWith(WORKSPACE_PREFIX)
                ? workspace.resolve(trimmed.substring(WORKSPACE_PREFIX.length()))
                : workingDirectory.resolve(trimmed);
        return pathEntry(raw, path, directory);
    }

    private static Entry pathEntry(String shown, Path path, boolean directory) {
        Path real = realPath(path);
        String trimmed = shown.replaceAll("/+$", "");
        // A directory is mentioned by its path, a file by its name
        String text = directory
                ? Pattern.quote(trimmed) + "|" + Pattern.quote(real.toString())
                : Pattern.quote(real.getFileName() == null ? trimmed : real.getFileName().toString());
        return new Entry(shown, real, directory, null, mention(text));
    }

    private static boolean matches(Entry entry, Path path) {
        if (entry.path() == null) {
            Path name = path.getFileName();
            return name != null && entry.fileName().matcher(name.toString()).matches();
        }
        return entry.directory() ? path.startsWith(entry.path()) : path.equals(entry.path());
    }

    private Optional<Path> asPath(String value) {
        String text = value.strip();
        if (text.isEmpty() || text.length() > LONGEST_PATH || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        if (text.startsWith("~/")) {
            text = System.getProperty("user.home") + text.substring(1);
        }
        try {
            Path path = Path.of(text);
            return Optional.of(realPath(path.isAbsolute() ? path : workingDirectory.resolve(path)));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** {@code *} matches any run of file-name characters; everything else is literal. */
    private static String glob(String name) {
        StringBuilder regex = new StringBuilder();
        for (String part : name.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(NAME_CHAR).append('*');
            }
            regex.append(Pattern.quote(part));
        }
        return regex.toString();
    }

    private static Pattern mention(String regex) {
        return Pattern.compile("(?<!" + NAME_CHAR + ")(?:" + regex + ")(?![\\w-])", Pattern.CASE_INSENSITIVE);
    }

    /** Every string in the arguments, at any depth; arguments that are not JSON count as one string. */
    private static List<String> strings(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        try {
            collect(JSON.readValue(arguments, Object.class), strings);
        } catch (JacksonException e) {
            strings.add(arguments);
        }
        return strings;
    }

    private static void collect(Object value, List<String> into) {
        if (value instanceof String s) {
            into.add(s);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, into));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collect(v, into));
        }
    }
}
