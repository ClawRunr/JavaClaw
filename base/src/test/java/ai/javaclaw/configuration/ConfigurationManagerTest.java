package ai.javaclaw.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationManagerTest {

    @TempDir
    Path configDir;

    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;

    private ConfigurationManager managerAt(String configLocation) {
        MockEnvironment environment = new MockEnvironment();
        if (configLocation != null) {
            environment.setProperty("spring.config.location", configLocation);
        }
        return new ConfigurationManager(environment, eventPublisher);
    }

    // -----------------------------------------------------------------------
    // spring.config.location is honoured when resolving the config file
    //
    // Every assertion below keys off MARKER, which only ever exists in the
    // per-test temporary directory. Asserting on a property the real
    // application.private.yaml also carries would let these tests pass by
    // reading the fallback location instead of the configured one.
    // -----------------------------------------------------------------------

    private static final String MARKER = "javaclaw-configuration-manager-test";

    private Path seedConfigFile(String fileName) throws IOException {
        Path configFile = configDir.resolve(fileName);
        Files.writeString(configFile, "marker: " + MARKER + "\n");
        return configFile;
    }

    @Test
    void readsFromTheLocationPointingAtAFile() throws IOException {
        Path configFile = seedConfigFile("application.private.yaml");

        Map<String, Object> config = managerAt(configFile.toString()).readApplicationYaml();

        assertThat(config).containsEntry("marker", MARKER);
    }

    @Test
    void readsFromTheLocationPointingAtADirectory() throws IOException {
        seedConfigFile("application.private.yaml");

        Map<String, Object> config = managerAt(configDir.toString()).readApplicationYaml();

        assertThat(config).containsEntry("marker", MARKER);
    }

    @Test
    void stripsTheFilePrefixFromTheLocation() throws IOException {
        Path configFile = seedConfigFile("application.private.yaml");

        Map<String, Object> config = managerAt("file:" + configFile).readApplicationYaml();

        assertThat(config).containsEntry("marker", MARKER);
    }

    @Test
    void usesTheFirstEntryOfACommaSeparatedLocationList() throws IOException {
        Path configFile = seedConfigFile("application.private.yaml");

        Map<String, Object> config = managerAt(configFile + ",classpath:/other.yaml").readApplicationYaml();

        assertThat(config).containsEntry("marker", MARKER);
    }

    // -----------------------------------------------------------------------
    // Writing goes to the same place reading does
    // -----------------------------------------------------------------------

    @Test
    void writesToTheConfiguredLocation() throws IOException {
        ConfigurationManager manager = managerAt(configDir.toString());

        manager.updateProperty("agent.onboarding.completed", true);

        Path written = configDir.resolve("application.private.yaml");
        assertThat(written).exists();
        assertThat(Files.readString(written)).contains("completed: true");
    }

    @Test
    void writesNestedKeysWithoutDiscardingSiblings() throws IOException {
        ConfigurationManager manager = managerAt(configDir.toString());

        manager.updateProperty("spring.ai.model.chat", "ollama");
        manager.updateProperty("spring.ai.ollama.chat.options.model", "gemma4:12b");

        Map<String, Object> reloaded = manager.readApplicationYaml();
        assertThat(reloaded).containsKey("spring");
        String written = Files.readString(configDir.resolve("application.private.yaml"));
        assertThat(written).contains("chat: ollama").contains("model: gemma4:12b");
    }

    @Test
    void updatesSeveralPropertiesInASingleWrite() throws IOException {
        ConfigurationManager manager = managerAt(configDir.toString());

        manager.updateProperties(Map.of("agent.onboarding.completed", true, "spring.ai.model.chat", "ollama"));

        String written = Files.readString(configDir.resolve("application.private.yaml"));
        assertThat(written).contains("completed: true").contains("chat: ollama");
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void publishesAConfigurationChangedEventOnEveryWrite() throws IOException {
        ConfigurationManager manager = managerAt(configDir.toString());

        manager.updateProperty("agent.onboarding.completed", true);

        assertThat(publishedEvents).hasSize(1).allSatisfy(event ->
                assertThat(event).isInstanceOf(ConfigurationChangedEvent.class));
    }

    // -----------------------------------------------------------------------
    // First run, before any config file has been written
    //
    // This covers a configured location whose file does not exist yet. The
    // separate branch where no location is configured at all resolves to a
    // hardcoded path inside the source tree, so it cannot be asserted without
    // writing there; it is left uncovered on purpose.
    // -----------------------------------------------------------------------

    @Test
    void returnsAnEmptyConfigWhenTheConfiguredFileDoesNotExistYet() throws IOException {
        Map<String, Object> config = managerAt(configDir.resolve("does-not-exist.yaml").toString()).readApplicationYaml();

        assertThat(config).isEmpty();
    }
}
