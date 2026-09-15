package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.web.client.RestClient;

import java.util.List;

public class OpenAIAgentOnboardingProvider implements AgentOnboardingProvider {

    // OpenAiSetup.OPENAI_URL (https://api.openai.com/v1) is package-private to
    // org.springframework.ai.openai.setup, so the API root is mirrored here.
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final RestClient restClient;

    public OpenAIAgentOnboardingProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String getId() {
        return "openai";
    }

    @Override
    public String getLabel() {
        return "OpenAI";
    }

    @Override
    public String slogan() {
        return "Uses OpenAI API key for ChatGPT as an agent.";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public List<String> availableModels(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            OpenAiModelsResponse response = restClient.get()
                    .uri(effectiveBase(baseUrl) + "/v1/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(OpenAiModelsResponse.class);
            if (response == null || response.data() == null) {
                return List.of();
            }
            List<String> ids = response.data().stream()
                    .map(OpenAiModel::id)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String effectiveBase(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    record OpenAiModelsResponse(List<OpenAiModel> data) {}
    record OpenAiModel(String id) {}
}
