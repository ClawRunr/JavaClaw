package ai.javaclaw.configuration;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published after the running {@link org.springframework.core.env.Environment} has been reloaded
 * and all {@code @ConfigurationProperties} beans have been rebound from the freshly written
 * configuration.
 * <p>
 * Beans that perform runtime work based on configuration (channels opening a connection, the agent
 * building its chat client, ...) should listen for this event and re-initialise their internal state
 * so that configuration changes take effect without restarting the whole application.
 * </p>
 *
 * @see ConfigurationRebinder
 */
public class ConfigurationRefreshedEvent extends ApplicationEvent {

    private final Set<String> changedKeys;

    public ConfigurationRefreshedEvent(Object source, Set<String> changedKeys) {
        super(source);
        this.changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
    }

    /**
     * The set of fully-qualified property keys (dotted notation) that changed in this refresh,
     * e.g. {@code "agent.channels.telegram.token"}.
     */
    public Set<String> changedKeys() {
        return changedKeys;
    }

    /**
     * Whether any changed key starts with the given prefix. Allows a listener to react only to the
     * configuration it cares about, e.g. {@code hasChangeUnder("agent.channels.telegram")}.
     */
    public boolean hasChangeUnder(String prefix) {
        return changedKeys.stream().anyMatch(key -> key.equals(prefix) || key.startsWith(prefix + "."));
    }
}
