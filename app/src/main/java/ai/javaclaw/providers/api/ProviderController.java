package ai.javaclaw.providers.api;

import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only REST API for the configured LLM providers under {@code agent.llm.providers}. Providers
 * are created/edited as part of agents (see {@code SubagentController}) or in configuration; this
 * endpoint exposes them (e.g. to populate the model list on the agents page). The raw API key is
 * never returned — only a masked form.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final LlmProviderProperties properties;

    public ProviderController(LlmProviderProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public List<ProviderDto> list() {
        List<ProviderDto> result = new ArrayList<>();
        properties.getProviders().forEach((name, config) -> result.add(toDto(name, config)));
        return result;
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
}
