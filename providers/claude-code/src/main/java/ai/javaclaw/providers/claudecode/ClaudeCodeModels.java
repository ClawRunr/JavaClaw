package ai.javaclaw.providers.claudecode;

import java.util.Map;

/**
 * Pinned Claude Code model routes. Behind the loopback relay Claude Code applies its gateway
 * defaults (a 200K window), so a model that supports 1M context must be selected explicitly with
 * the {@code [1m]} suffix. Unknown models pass through unchanged and get the CLI's 200K default.
 */
final class ClaudeCodeModels {

    static final Map<String, Integer> CONTEXT_WINDOWS = Map.of(
            "claude-sonnet-5-5", 1_000_000,
            "claude-sonnet-5", 1_000_000,
            "claude-haiku-5-5", 1_000_000,
            "claude-haiku-4-5-20251001", 200_000,
            "claude-opus-5-5", 1_000_000,
            "claude-opus-5", 1_000_000,
            "claude-opus-4-8", 1_000_000,
            "claude-fable-5-1", 1_000_000);

    static final Map<String, String> ALIASES = Map.of(
            "sonnet", "claude-sonnet-5-5",
            "haiku", "claude-haiku-5-5",
            "claude-haiku-4-5", "claude-haiku-4-5-20251001",
            "opus", "claude-opus-5-5",
            "fable", "claude-fable-5-1");

    /** The pinned routes offered in the model picker, newest families first, as {@code id -> label}. */
    static final Map<String, String> CHOICES = orderedChoices();

    private static final String LONG_CONTEXT = "[1m]";

    private static Map<String, String> orderedChoices() {
        Map<String, String> choices = new java.util.LinkedHashMap<>();
        choices.put("claude-sonnet-5-5", "Sonnet 5.5 (recommended)");
        choices.put("claude-opus-5-5", "Opus 5.5");
        choices.put("claude-haiku-5-5", "Haiku 5.5 (needs Claude Code 2.1.293 or newer)");
        choices.put("claude-fable-5-1", "Fable 5.1 (billed to usage credits on Pro)");
        choices.put("claude-sonnet-5", "Sonnet 5");
        choices.put("claude-opus-5", "Opus 5");
        choices.put("claude-opus-4-8", "Opus 4.8");
        choices.put("claude-haiku-4-5-20251001", "Haiku 4.5 (200K context)");
        return java.util.Collections.unmodifiableMap(choices);
    }

    private ClaudeCodeModels() {
    }

    /** The {@code --model} value Claude Code gets for a configured model name or alias. */
    static String nativeModel(String model) {
        String base = routeBase(stripLongContext(model));
        String canonical = ALIASES.getOrDefault(base, base);
        Integer window = CONTEXT_WINDOWS.get(canonical);
        if (window == null) {
            return model;
        }
        if (window == 1_000_000) {
            return canonical + LONG_CONTEXT;
        }
        if (model.endsWith(LONG_CONTEXT)) {
            throw new IllegalArgumentException(canonical + " does not support a 1M context window");
        }
        return canonical;
    }

    /**
     * The pinned id a short name points at ({@code sonnet-5.5} and {@code sonnet-5-5} both name
     * {@code claude-sonnet-5-5}), or the name unchanged so an unknown route still reaches the CLI.
     */
    private static String routeBase(String base) {
        if (ALIASES.containsKey(base) || CONTEXT_WINDOWS.containsKey(base)) {
            return base;
        }
        String dashed = base.replace('.', '-');
        if (!dashed.equals(base)) {
            return routeBase(dashed);
        }
        String vendored = "claude-" + (base.startsWith("claude-") ? base.substring("claude-".length()) : base);
        if (CONTEXT_WINDOWS.containsKey(vendored) || ALIASES.containsKey(vendored)) {
            return vendored;
        }
        return base;
    }

    private static String stripLongContext(String model) {
        return model.endsWith(LONG_CONTEXT) ? model.substring(0, model.length() - LONG_CONTEXT.length()) : model;
    }
}
