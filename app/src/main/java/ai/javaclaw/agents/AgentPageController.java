package ai.javaclaw.agents;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentStore;
import ai.javaclaw.llm.SubagentStore.Subagent;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/settings/agents")
public class AgentPageController {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9-]+");
    private static final String PROVIDERS_PREFIX = "agent.llm.providers";

    static class ValidationException extends RuntimeException {
        ValidationException(String message) {
            super(message);
        }
    }

    static class NotFoundException extends RuntimeException {
    }

    private final SubagentStore store;
    private final AgentOnboardingProviders providers;
    private final LlmProviderProperties providerProperties;
    private final ConfigurationManager configurationManager;

    public AgentPageController(SubagentStore store,
                               AgentOnboardingProviders providers,
                               LlmProviderProperties providerProperties,
                               ConfigurationManager configurationManager) {
        this.store = store;
        this.providers = providers;
        this.providerProperties = providerProperties;
        this.configurationManager = configurationManager;
    }

    @GetMapping("/fragments/list")
    public String listFragment(Model model) {
        return populateList(model);
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        populateDrawer(model, null, false);
        return "settings/agents/drawer";
    }

    @GetMapping("/{name}/edit")
    public String editForm(@PathVariable String name, Model model) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new NotFoundException();
        }
        populateDrawer(model, toDetail(name), true);
        return "settings/agents/drawer";
    }

    @PostMapping
    public String create(@RequestParam Map<String, String> form, Model model, HttpServletResponse response) {
        String name;
        try {
            name = validateNewName(form.get("name"));
            validateProvider(form.get("provider"));
        } catch (ValidationException e) {
            return drawerError(model, response, form, e.getMessage(), false);
        }
        save(name, form);
        return populateList(model);
    }

    @PutMapping("/{name}")
    public String update(@PathVariable String name, @RequestParam Map<String, String> form,
                         Model model, HttpServletResponse response) {
        requireExisting(name);
        try {
            validateProvider(form.get("provider"));
        } catch (ValidationException e) {
            return drawerError(model, response, form, e.getMessage(), true);
        }
        save(name, form);
        return populateList(model);
    }

    @DeleteMapping("/{name}")
    public String delete(@PathVariable String name, Model model) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new NotFoundException();
        }
        try {
            boolean removedFile = store.delete(name);
            boolean hadConfig = providerProperties.getProviders().containsKey(name);
            if (!removedFile && !hadConfig) {
                throw new NotFoundException();
            }
            configurationManager.removeProperty(PROVIDERS_PREFIX + "." + name);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete agent " + name, e);
        }
        return populateList(model);
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound() {
    }

    private String drawerError(Model model, HttpServletResponse response,
                               Map<String, String> form, String error, boolean isEdit) {
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        response.setHeader("HX-Retarget", "#agent-drawer");
        populateDrawer(model, form, isEdit);
        model.addAttribute("error", error);
        return "settings/agents/drawer";
    }

    private void save(String name, Map<String, String> form) {
        try {
            store.save(new Subagent(name, form.get("model"), form.get("description"), form.get("content")));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save agent " + name, e);
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PROVIDERS_PREFIX + "." + name + ".provider", form.get("provider"));
        if (hasText(form.get("baseUrl"))) {
            props.put(PROVIDERS_PREFIX + "." + name + ".base-url", form.get("baseUrl").trim());
        }
        if (hasText(form.get("apiKey"))) {
            props.put(PROVIDERS_PREFIX + "." + name + ".api-key", form.get("apiKey").trim());
        }
        if (hasText(form.get("model"))) {
            props.put(PROVIDERS_PREFIX + "." + name + ".model", form.get("model").trim());
        }
        try {
            configurationManager.updateProperties(props);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save agent " + name, e);
        }
    }

    private String validateNewName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isBlank() || !NAME_PATTERN.matcher(name).matches()) {
            throw new ValidationException("Agent name must match [a-z0-9-]+");
        }
        if (providerProperties.getProviders().containsKey(name) || store.exists(name)) {
            throw new ValidationException("An agent named '" + name + "' already exists");
        }
        return name;
    }

    private void validateProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new ValidationException("A provider is required");
        }
        if (providers.findById(provider).isEmpty()) {
            throw new ValidationException("Unknown provider: " + provider);
        }
    }

    private void requireExisting(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches() || !store.exists(name)) {
            throw new NotFoundException();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String populateList(Model model) {
        List<Map<String, String>> cards = new ArrayList<>();
        for (Subagent s : store.list()) {
            ProviderConfig config = providerProperties.getProviders().get(s.name());
            String provider = config != null ? config.getProvider() : null;
            String modelId = config != null ? config.getModel() : null;
            Map<String, String> card = new LinkedHashMap<>();
            card.put("name", s.name());
            card.put("sub", agentSub(provider, modelId, s.description()));
            cards.add(card);
        }
        model.addAttribute("agents", cards);
        return "settings/agents/list";
    }

    private String agentSub(String provider, String modelId, String description) {
        String sub = labelFor(provider) + " · " + (modelId == null || modelId.isBlank() ? "—" : modelId);
        if (description != null && !description.isBlank()) {
            sub += " · " + description;
        }
        return sub;
    }

    private void populateDrawer(Model model, Map<String, String> values, boolean isEdit) {
        String name = values == null ? "" : values.getOrDefault("name", "");
        model.addAttribute("isEdit", isEdit);
        model.addAttribute("providers", providerOptions());
        model.addAttribute("drawerTitle", isEdit ? "Edit " + name : "Add Agent");
        model.addAttribute("formAction", isEdit ? "/settings/agents/" + name : "/settings/agents");
        model.addAttribute("agentName", name);
        model.addAttribute("nameReadonly", isEdit);
        model.addAttribute("selectedProvider", values == null ? "" : values.getOrDefault("provider", ""));
        model.addAttribute("baseUrl", values == null ? "" : values.getOrDefault("baseUrl", ""));
        model.addAttribute("model", values == null ? "" : values.getOrDefault("model", ""));
        model.addAttribute("description", values == null ? "" : values.getOrDefault("description", ""));
        model.addAttribute("content", values == null ? "" : values.getOrDefault("content", ""));
        model.addAttribute("apiKeyMasked", values == null ? "" : values.getOrDefault("apiKeyMasked", ""));
    }

    private Map<String, String> toDetail(String name) {
        ProviderConfig config = providerProperties.getProviders().get(name);
        Subagent agent = store.get(name).orElseThrow(NotFoundException::new);
        String provider = config != null ? config.getProvider() : "";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", agent.name());
        values.put("provider", provider);
        values.put("baseUrl", config != null && config.getBaseUrl() != null ? config.getBaseUrl() : "");
        values.put("model", config != null && config.getModel() != null ? config.getModel() : "");
        values.put("description", agent.description() == null ? "" : agent.description());
        values.put("content", agent.content() == null ? "" : agent.content());
        values.put("apiKeyMasked", config != null ? maskApiKey(config.getApiKey()) : "");
        return values;
    }

    private List<Map<String, String>> providerOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (AgentOnboardingProvider p : providers.getAll()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("label", p.getLabel());
            m.put("defaultModel", p.defaultModel());
            options.add(m);
        }
        return options;
    }

    private String labelFor(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "";
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
}