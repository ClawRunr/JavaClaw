package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.web.client.RestClient;

import java.util.List;

public class OllamaAgentOnboardingProvider implements AgentOnboardingProvider {

    // Spring AI's OllamaConnectionProperties defaults to this value but does not expose it as a
    // public constant, so it is mirrored here.
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final RestClient restClient;

    public OllamaAgentOnboardingProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String getId() {
        return "ollama";
    }

    @Override
    public String getLabel() {
        return "Ollama";
    }

    @Override
    public String slogan() {
        return "Local-first setup. No API key required.";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<String> availableModels(String baseUrl, String apiKey) {
        try {
            OllamaTagsResponse response = restClient.get()
                    .uri(effectiveBase(baseUrl) + "/api/tags")
                    .retrieve()
                    .body(OllamaTagsResponse.class);
            if (response == null || response.models() == null) {
                return List.of();
            }
            List<String> names = response.models().stream()
                    .map(OllamaModel::name)
                    .filter(n -> n != null && !n.isBlank())
                    .toList();
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String effectiveBase(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    record OllamaTagsResponse(List<OllamaModel> models) {}
    record OllamaModel(String name, String model) {}
}
