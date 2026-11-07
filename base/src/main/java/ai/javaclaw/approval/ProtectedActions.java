package ai.javaclaw.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Calls that always ask, whatever the rules say, because they could switch the approval gate
 * itself off or widen what the agent can reach:
 * <ul>
 *   <li>adding or changing an MCP server ({@code McpTool});</li>
 *   <li>any call that is not read-only and writes to, or mentions, the configuration that holds
 *       {@code agent.approval.*} — a write to the runtime config file or any
 *       {@code application*.yaml|yml|properties}, or a shell command or subagent prompt naming one;</li>
 *   <li>any call that is not read-only and writes into a protected directory, such as the
 *       conversation history in {@code workspace/conversations/} the agent's memory is replayed from.</li>
 * </ul>
 * The text check is a tripwire for honest mistakes and casual injection, not a sandbox: a
 * determined shell command can build the file name at run time. That is why {@code Bash} and
 * {@code Task} are never allowed silently by default either.
 */
public class ProtectedActions {

    static final Set<String> MCP_CONFIG_TOOLS = Set.of("addStreamableHttpMcpServer", "addStdioMcpServer");

    /** Argument names tools use for the file they write. */
    private static final Set<String> PATH_ARGUMENTS = Set.of("filePath", "file_path", "path", "target", "destination");

    private static final Pattern CONFIG_MENTION = Pattern.compile(
            "application[\\w.-]*\\.(?:ya?ml|properties)|agent\\.approval", Pattern.CASE_INSENSITIVE);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path configFile;
    private final Map<Path, String> protectedDirectories;

    /** @param configFile the runtime configuration file {@code ConfigurationManager} writes */
    public ProtectedActions(Path configFile) {
        this(configFile, Map.of());
    }

    /**
     * @param configFile           the runtime configuration file {@code ConfigurationManager} writes
     * @param protectedDirectories directories no call may write into unasked, each with the name the
     *                             reason shows (for example {@code workspace/conversations})
     */
    public ProtectedActions(Path configFile, Map<Path, String> protectedDirectories) {
        this.configFile = normalize(configFile);
        Map<Path, String> directories = new java.util.LinkedHashMap<>();
        protectedDirectories.forEach((dir, name) -> directories.put(resolved(dir), name));
        this.protectedDirectories = Map.copyOf(directories);
    }

    /**
     * @return why the call must always ask, or empty when it is an ordinary call
     */
    public Optional<String> reason(String toolName, String arguments, Optional<ToolRisk> risk) {
        if (MCP_CONFIG_TOOLS.contains(toolName)) {
            return Optional.of("adds or changes an MCP server");
        }
        if (risk.equals(Optional.of(ToolRisk.READ_ONLY))) {
            return Optional.empty();
        }
        Map<String, Object> args = parse(arguments);
        for (String key : PATH_ARGUMENTS) {
            if (args.get(key) instanceof String path && isConfigFile(path)) {
                return Optional.of("writes the approval configuration (" + path + ")");
            }
        }
        for (String key : PATH_ARGUMENTS) {
            if (args.get(key) instanceof String path) {
                Optional<String> directory = protectedDirectoryOf(path);
                if (directory.isPresent()) {
                    return Optional.of("writes into " + directory.get() + "/");
                }
            }
        }
        List<String> texts = new ArrayList<>();
        collectStrings(args, texts);
        if (args.isEmpty() && arguments != null) {
            texts.add(arguments);
        }
        String configName = configFile.getFileName() == null ? null : configFile.getFileName().toString();
        for (String text : texts) {
            if (CONFIG_MENTION.matcher(text).find() || (configName != null && text.contains(configName))) {
                return Optional.of("mentions the approval configuration");
            }
        }
        return Optional.empty();
    }

    private boolean isConfigFile(String path) {
        Path target;
        try {
            target = normalize(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
        if (target.equals(configFile) || sameFile(target, configFile)) {
            return true;
        }
        Path name = target.getFileName();
        return name != null && CONFIG_MENTION.matcher(name.toString()).matches();
    }

    private Optional<String> protectedDirectoryOf(String path) {
        Path target;
        try {
            target = resolved(Path.of(path));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        return protectedDirectories.entrySet().stream()
                .filter(directory -> target.startsWith(directory.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * The absolute, normalized path with symlinks resolved as far as it exists, so neither
     * {@code ..} nor a link (or {@code /tmp} vs {@code /private/tmp}) can step around a check.
     */
    static Path resolved(Path path) {
        Path absolute = normalize(path);
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

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Object value = JSON.readValue(arguments, Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private static void collectStrings(Object value, List<String> into) {
        if (value instanceof String s) {
            into.add(s);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStrings(v, into));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectStrings(v, into));
        }
    }
}
