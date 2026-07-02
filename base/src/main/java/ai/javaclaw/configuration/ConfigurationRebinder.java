package ai.javaclaw.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes runtime configuration changes take effect without restarting the whole Spring context.
 * <p>
 * When the configuration file changes ({@link ConfigurationChangedEvent}) this component:
 * <ol>
 *     <li>reloads the file into the running {@link ConfigurableEnvironment} with the highest
 *         precedence, so the new values shadow the stale startup values;</li>
 *     <li>rebinds every {@code @ConfigurationProperties} bean from the refreshed environment
 *         (immutable / record-based beans are re-instantiated and swapped in the bean factory);</li>
 *     <li>publishes a {@link ConfigurationRefreshedEvent} so beans that hold runtime state
 *         (channels, the agent's chat client, ...) can re-initialise themselves.</li>
 * </ol>
 * This is a deliberately lightweight stand-in for Spring Cloud Context's
 * {@code ConfigurationPropertiesRebinder}, which is not on the classpath.
 */
@Component
public class ConfigurationRebinder {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationRebinder.class);

    /** Name of the property source we (re)inject on every reload. */
    static final String RUNTIME_PROPERTY_SOURCE = "javaclaw-runtime-config";

    private final ConfigurableApplicationContext applicationContext;
    private final ConfigurableEnvironment environment;
    private final ConfigurationManager configurationManager;
    private final ApplicationEventPublisher eventPublisher;

    public ConfigurationRebinder(ConfigurableApplicationContext applicationContext,
                                 ConfigurableEnvironment environment,
                                 ConfigurationManager configurationManager,
                                 ApplicationEventPublisher eventPublisher) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.configurationManager = configurationManager;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onConfigurationChanged(ConfigurationChangedEvent event) {
        rebind(event.changedKeys());
    }

    /**
     * Reloads the environment, rebinds all {@code @ConfigurationProperties} beans and announces the
     * refresh. Safe to call directly (e.g. from tests).
     */
    public synchronized void rebind(Set<String> changedKeys) {
        reloadEnvironment();
        rebindConfigurationProperties();
        log.info("Configuration reloaded and rebound ({} changed key(s)): {}", changedKeys.size(), changedKeys);
        eventPublisher.publishEvent(new ConfigurationRefreshedEvent(this, changedKeys));
    }

    private void reloadEnvironment() {
        Path path = configurationManager.getConfigPath();
        if (path == null || !Files.exists(path)) {
            log.debug("No configuration file at {} to reload", path);
            return;
        }

        try {
            List<PropertySource<?>> loaded =
                    new YamlPropertySourceLoader().load(RUNTIME_PROPERTY_SOURCE, new FileSystemResource(path));

            MutablePropertySources sources = environment.getPropertySources();
            // Drop the previously injected runtime source(s) before re-adding the fresh ones.
            sources.stream()
                    .map(PropertySource::getName)
                    .filter(name -> name.startsWith(RUNTIME_PROPERTY_SOURCE))
                    .toList()
                    .forEach(sources::remove);

            // Add with the highest precedence so the freshly written values win over startup sources.
            for (int i = loaded.size() - 1; i >= 0; i--) {
                sources.addFirst(loaded.get(i));
            }
        } catch (IOException e) {
            throw new ConfigurationRefreshException("Failed to reload configuration from " + path, e);
        }
    }

    private void rebindConfigurationProperties() {
        ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        Binder binder = Binder.get(environment);

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(ConfigurationProperties.class);
        beans.forEach((name, bean) -> rebindBean(beanFactory, binder, name, bean));
    }

    private void rebindBean(ConfigurableListableBeanFactory beanFactory, Binder binder, String name, Object bean) {
        ConfigurationProperties annotation =
                applicationContext.findAnnotationOnBean(name, ConfigurationProperties.class);
        if (annotation == null) {
            return;
        }
        String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();

        try {
            if (bean.getClass().isRecord()) {
                // Records (and other immutable, constructor-bound beans) cannot be mutated in place;
                // re-create the instance and swap it into the singleton registry.
                Object rebound = binder.bind(prefix, Bindable.of(bean.getClass())).orElse(null);
                if (rebound != null && !rebound.equals(bean)) {
                    if (beanFactory instanceof DefaultSingletonBeanRegistry singletonRegistry) {
                        singletonRegistry.destroySingleton(name);
                    }
                    beanFactory.registerSingleton(name, rebound);
                    log.debug("Rebound (replaced) immutable @ConfigurationProperties bean '{}'", name);
                }
            } else {
                binder.bind(prefix, Bindable.ofInstance(bean));
                log.debug("Rebound @ConfigurationProperties bean '{}' in place", name);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to rebind @ConfigurationProperties bean '{}' (prefix '{}')", name, prefix, e);
        }
    }

    static class ConfigurationRefreshException extends RuntimeException {
        ConfigurationRefreshException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
