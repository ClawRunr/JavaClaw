package ai.javaclaw.providers.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentStore;
import ai.javaclaw.llm.SubagentStore.Subagent;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST API for the agents page. Each agent's structured configuration (provider type, model, API key)
 * is stored in {@code application.yaml} under {@code agent.llm.providers.<name>}, and its description +
 * instructions are stored in {@code workspace/agents/<name>.md}. The agent's subagent file routes to
 * its own provider entry (its {@code model:} frontmatter is the agent name), so it runs on its own
 * provider/model/key. Provider options for the dropdown come from the supported
 * {@link AgentOnboardingProvider} beans.
 */
@RestController
@RequestMapping("/api/agents")
public class SubagentController {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9-]+");
    private static final String PROVIDERS_PREFIX = "agent.llm.providers";

    private final SubagentStore store;
    private final AgentOnboardingProviders providers;
    private final LlmProviderProperties providerProperties;
    private final ConfigurationManager configurationManager;

    public SubagentController(SubagentStore store,
                              AgentOnboardingProviders providers,
                              LlmProviderProperties providerProperties,
                              ConfigurationManager configurationManager) {
        this.store = store;
        this.providers = providers;
        this.providerProperties = providerProperties;
        this.configurationManager = configurationManager;
    }

    public record AgentSummary(String name, String provider, String providerLabel, String model, String description) {
    }

    public record AgentDetail(String name, String provider, String providerLabel, String model, String apiKeyMasked,
                              String description, String content) {
    }

    /** Request body for create/update. */
    public record AgentForm(String name, String provider, String model, String apiKey, String description,
                            String content) {
    }

    /** A selectable LLM provider for the dropdown. */
    public record ProviderOption(String id, String label, String defaultModel) {
    }

    @GetMapping("/options/providers")
    public List<ProviderOption> providerOptions() {
        return providers.getAll().stream()
                .map(p -> new ProviderOption(p.getId(), p.getLabel(), p.defaultModel()))
                .toList();
    }

    @GetMapping
    public List<AgentSummary> list() {
        return store.list().stream().map(s -> {
            ProviderConfig config = providerProperties.getProviders().get(s.name());
            String provider = config != null ? config.getProvider() : null;
            String model = config != null ? config.getModel() : null;
            return new AgentSummary(s.name(), provider, labelFor(provider), model, s.description());
        }).toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<AgentDetail> get(@PathVariable String name) {
        if (!isValidName(name)) {
            return ResponseEntity.notFound().build();
        }
        return store.get(name)
                .map(s -> {
                    ProviderConfig config = providerProperties.getProviders().get(name);
                    String provider = config != null ? config.getProvider() : null;
                    String model = config != null ? config.getModel() : null;
                    String maskedKey = config != null ? maskApiKey(config.getApiKey()) : "";
                    return ResponseEntity.ok(new AgentDetail(s.name(), provider, labelFor(provider), model,
                            maskedKey, s.description(), s.content()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AgentForm form) {
        String name = form.name() == null ? "" : form.name().trim();
        if (name.isBlank() || !NAME_PATTERN.matcher(name).matches()) {
            return badRequest("Agent name must match [a-z0-9-]+");
        }
        if (providerProperties.getProviders().containsKey(name) || store.exists(name)) {
            return badRequest("An agent named '" + name + "' already exists");
        }
        String error = validateProvider(form.provider());
        if (error != null) {
            return badRequest(error);
        }
        return save(name, form, HttpStatus.CREATED);
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody AgentForm form) {
        if (!isValidName(name) || !store.exists(name)) {
            return ResponseEntity.notFound().build();
        }
        String error = validateProvider(form.provider());
        if (error != null) {
            return badRequest(error);
        }
        return save(name, form, HttpStatus.OK);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        if (!isValidName(name)) {
            return ResponseEntity.notFound().build();
        }
        try {
            boolean removedFile = store.delete(name);
            boolean removedConfig = providerProperties.getProviders().containsKey(name);
            if (!removedFile && !removedConfig) {
                return ResponseEntity.notFound().build();
            }
            // Removing the provider entry fires a configuration change that rebuilds routing.
            configurationManager.removeProperty(PROVIDERS_PREFIX + "." + name);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(error("Failed to delete agent: " + e.getMessage()));
        }
    }

    private ResponseEntity<?> save(String name, AgentForm form, HttpStatus status) {
        try {
            // 1) Instructions + description -> workspace/agents/<name>.md. The subagent routes to its
            //    own provider entry, so its routing model is the agent name.
            store.save(new Subagent(name, name, form.description(), form.content()));

            // 2) Structured config -> application.yaml under agent.llm.providers.<name>. This fires a
            //    configuration change that rebuilds the chat client registry + subagent routing.
            String base = PROVIDERS_PREFIX + "." + name;
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(base + ".provider", form.provider());
            if (notBlank(form.model())) {
                props.put(base + ".model", form.model().trim());
            }
            if (notBlank(form.apiKey())) {
                props.put(base + ".api-key", form.apiKey().trim());
            }
            configurationManager.updateProperties(props);

            ProviderConfig saved = providerProperties.getProviders().get(name);
            String maskedKey = saved != null ? maskApiKey(saved.getApiKey()) : maskApiKey(form.apiKey());
            return ResponseEntity.status(status).body(new AgentDetail(name, form.provider(), labelFor(form.provider()),
                    orEmpty(form.model()), maskedKey, orEmpty(form.description()), orEmpty(form.content())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(error("Failed to save agent: " + e.getMessage()));
        }
    }

    private String validateProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "A provider is required";
        }
        if (providers.findById(provider).isEmpty()) {
            return "Unknown provider: " + provider;
        }
        return null;
    }

    private boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    private String labelFor(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return providerId;
        }
        return providers.findById(providerId).map(AgentOnboardingProvider::getLabel).orElse(providerId);
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

    private static String orEmpty(String value) {
        return value == null ? "" : value;
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
