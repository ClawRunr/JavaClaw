package ai.javaclaw.providers.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentReferenceScanner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REST API for managing the named LLM providers under {@code agent.llm.providers}. Saving fires a
 * {@code ConfigurationChangedEvent}, which the {@code ConfigurationRebinder} turns into a
 * {@code ConfigurationRefreshedEvent} so the {@code ChatClientRegistry} rebuilds affected clients.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9-]+");
    private static final String CONFIG_PREFIX = "agent.llm.providers";

    private final LlmProviderProperties properties;
    private final ConfigurationManager configurationManager;
    private final SubagentReferenceScanner subagentScanner;
    private final List<ChatModelFactory> chatModelFactories;

    public ProviderController(LlmProviderProperties properties,
                              ConfigurationManager configurationManager,
                              SubagentReferenceScanner subagentScanner,
                              List<ChatModelFactory> chatModelFactories) {
        this.properties = properties;
        this.configurationManager = configurationManager;
        this.subagentScanner = subagentScanner;
        this.chatModelFactories = chatModelFactories;
    }

    private Map<String, ProviderConfig> currentProviders() {
        return properties.getProviders();
    }

    @GetMapping
    public List<ProviderDto> list() {
        List<ProviderDto> result = new ArrayList<>();
        currentProviders().forEach((name, config) -> result.add(toDto(name, config)));
        return result;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ProviderForm form) {
        String name = form.name() == null ? "" : form.name().trim();
        if (name.isBlank() || !NAME_PATTERN.matcher(name).matches()) {
            return badRequest("Provider name must match [a-z0-9-]+");
        }
        if (LlmProviderProperties.DEFAULT_PROVIDER_NAME.equals(name)) {
            return badRequest("The 'default' provider is created during onboarding and can only be edited");
        }
        if (currentProviders().containsKey(name)) {
            return badRequest("A provider named '" + name + "' already exists");
        }
        if (!isKnownProviderType(form.provider())) {
            return badRequest("Unknown provider type: " + form.provider());
        }

        save(name, form);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(name, form.toConfig()));
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody ProviderForm form) {
        ProviderConfig existing = currentProviders().get(name);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Name and provider type are immutable on edit.
        ProviderForm effective = new ProviderForm(name, existing.getProvider(), form.model(), form.apiKey(), form.baseUrl());
        save(name, effective);
        return ResponseEntity.ok(toDto(name, effective.toConfig()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name,
                                    @RequestParam(defaultValue = "false") boolean force) {
        if (!currentProviders().containsKey(name)) {
            return ResponseEntity.notFound().build();
        }
        if (LlmProviderProperties.DEFAULT_PROVIDER_NAME.equals(name)) {
            return badRequest("The default provider cannot be removed");
        }

        Set<String> referencedBy = subagentScanner.namesReferencing(name);
        if (!referencedBy.isEmpty() && !force) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "'" + name + "' is referenced by " + referencedBy.size() + " subagent(s)");
            body.put("affected", new ArrayList<>(referencedBy));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        try {
            configurationManager.removeProperty(CONFIG_PREFIX + "." + name);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(error("Failed to update configuration: " + e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---

    private void save(String name, ProviderForm form) {
        String base = CONFIG_PREFIX + "." + name;
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(base + ".provider", form.provider());
        if (notBlank(form.model())) props.put(base + ".model", form.model());
        if (notBlank(form.apiKey())) props.put(base + ".api-key", form.apiKey());
        if (notBlank(form.baseUrl())) props.put(base + ".base-url", form.baseUrl());
        try {
            configurationManager.updateProperties(props);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write provider configuration", e);
        }
    }

    private boolean isKnownProviderType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        return chatModelFactories.stream().anyMatch(f -> f.supports(normalized));
    }

    private ProviderDto toDto(String name, ProviderConfig config) {
        boolean isDefault = LlmProviderProperties.DEFAULT_PROVIDER_NAME.equals(name);
        return new ProviderDto(name, config.getProvider(), config.getModel(),
                config.getBaseUrl(), maskApiKey(config.getApiKey()), isDefault);
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 7) {
            return "••••";
        }
        return key.substring(0, 3) + "..." + key.substring(key.length() - 4);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(error(message));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return body;
    }
}
