package ai.javaclaw.providers.google.genai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public class GoogleGenAIAgentOnboardingProvider implements AgentOnboardingProvider {

    // com.google.genai.ApiClient hardcodes this endpoint internally with no exported constant,
    // so it must be mirrored here.
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final RestClient restClient;

    public GoogleGenAIAgentOnboardingProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String getId() {
        return "google.genai";
    }

    @Override
    public String getLabel() {
        return "Google Gen AI";
    }

    @Override
    public String slogan() {
        return "Use Google Gen AI (like Gemini) as an agent";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public Optional<List<String>> availableModels(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(effectiveBase(baseUrl))
                    .path("/v1beta/models")
                    .queryParam("key", apiKey)
                    .build()
                    .toUri();
            GoogleModelsResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(GoogleModelsResponse.class);
            if (response == null || response.models() == null) {
                return Optional.empty();
            }
            List<String> names = response.models().stream()
                    .filter(m -> m.supportedGenerationMethods() != null
                            && m.supportedGenerationMethods().contains("generateContent"))
                    .map(GoogleModel::name)
                    .filter(n -> n != null && n.startsWith("models/"))
                    .map(n -> n.substring("models/".length()))
                    .filter(n -> !n.isBlank())
                    .toList();
            return names.isEmpty() ? Optional.empty() : Optional.of(names);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String effectiveBase(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    record GoogleModelsResponse(List<GoogleModel> models) {}
    record GoogleModel(String name, List<String> supportedGenerationMethods) {}
}
