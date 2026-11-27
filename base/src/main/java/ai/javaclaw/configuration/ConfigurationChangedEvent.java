package ai.javaclaw.configuration;

import java.util.Map;
import java.util.Set;

/**
 * Published by {@link ConfigurationManager} whenever the configuration file is written.
 *
 * @param allConfig   the full configuration tree as it was written to disk
 * @param changedKeys the fully-qualified (dotted) keys that were updated in this write,
 *                    e.g. {@code "agent.llm.providers.default.model"}
 */
public record ConfigurationChangedEvent(Map<String, Object> allConfig, Set<String> changedKeys) {

    public ConfigurationChangedEvent {
        changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
    }

    /**
     * Backwards-compatible constructor for callers that only have the full configuration tree.
     */
    public ConfigurationChangedEvent(Map<String, Object> allConfig) {
        this(allConfig, Set.of());
    }

    public Object getConfiguration(String key) {
        String[] keys = key.split("\\.");
        Map<String, Object> map = allConfig;
        for (int i = 0; i < keys.length - 1; i++) {
            Object configItem = map.get(keys[i]);
            if (configItem == null) return null;
            else if (configItem instanceof Map<?, ?> nestedMap) {
                map = (Map<String, Object>) nestedMap;
            }
        }
        return map.get(keys[keys.length - 1]);
    }

    /**
     * Whether any changed key equals or is nested under the given prefix.
     */
    public boolean hasChangeUnder(String prefix) {
        return changedKeys.stream().anyMatch(key -> key.equals(prefix) || key.startsWith(prefix + "."));
    }
}
