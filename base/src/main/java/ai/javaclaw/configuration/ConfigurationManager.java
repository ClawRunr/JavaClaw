package ai.javaclaw.configuration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ConfigurationManager {

    private final Path configPath;
    private final ApplicationEventPublisher eventPublisher;

    public ConfigurationManager(Environment environment, ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.configPath = resolveConfigPath(environment.getProperty("spring.allConfig.location"));
    }

    public void updateProperty(String key, Object value) throws IOException {
        Map<String, Object> config = readApplicationYaml();

        setNestedValue(config, key.split("\\."), value);

        writeApplicationYaml(config);
    }

    public void updateProperties(Map<String, Object> keyValues) throws IOException {
        Map<String, Object> config = readApplicationYaml();

        keyValues.forEach((k, v) -> setNestedValue(config, k.split("\\."), v));

        writeApplicationYaml(config);
    }

    public Map<String, Object> readApplicationYaml() throws IOException {
        Yaml yaml = new Yaml();

        Map<String, Object> config;
        try (FileReader input = new FileReader(configPath.toFile())) {
            config = yaml.load(input);
        } catch (FileNotFoundException e) {
            // inital onboarding
            config = null;
        }
        return config == null ? new LinkedHashMap<>() : config;
    }

    private void writeApplicationYaml(Map<String, Object> config) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml writer = new Yaml(options);

        Files.createDirectories(configPath.getParent());

        try (FileWriter fw = new FileWriter(configPath.toFile())) {
            writer.dump(config, fw);
        }

        eventPublisher.publishEvent(new ConfigurationChangedEvent(config));
    }

    private Path resolveConfigPath(String location) {
        if (location == null || location.isBlank()) {
            return Path.of("app", "src", "main", "resources", "application.private.yaml");
        }

        String candidate = location.split(",")[0].trim().replace("file:", "");
        Path path = Path.of(candidate);

        if (Files.isDirectory(path) || candidate.endsWith("/")) {
            return path.resolve("application.private.yaml");
        }

        return path;
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String[] keys, Object value) {
        for (int i = 0; i < keys.length - 1; i++) {
            map = (Map<String, Object>) map.computeIfAbsent(keys[i], k -> new LinkedHashMap<>());
        }
        map.put(keys[keys.length - 1], value);
    }
}
