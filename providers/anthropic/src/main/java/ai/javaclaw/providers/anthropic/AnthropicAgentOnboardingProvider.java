package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static ai.javaclaw.providers.anthropic.AnthropticClaudeCodeConfiguration.CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER;

public class AnthropicAgentOnboardingProvider implements AgentOnboardingProvider {

    // AnthropicSetup.ANTHROPIC_URL is package-private to org.springframework.ai.anthropic, so mirrored here.
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;

    public AnthropicAgentOnboardingProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String getId() {
        return "anthropic";
    }

    @Override
    public String getLabel() {
        return "Anthropic";
    }

    @Override
    public String slogan() {
        return "Uses your existing Claude Code or Anthropic API-key for Claude-based chat";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public Optional<SystemWideToken> systemWideToken() {
        Optional<String> token = AnthropicClaudeCodeOAuthTokenExtractor.getToken();
        if (token.isEmpty()) return Optional.empty();

        return Optional.of(new SystemWideToken("Claude Code", CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER));
    }

    @Override
    public Optional<List<String>> availableModels(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            AnthropicModelsResponse response = restClient.get()
                    .uri(effectiveBase(baseUrl) + "/v1/models")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .retrieve()
                    .body(AnthropicModelsResponse.class);
            if (response == null || response.data() == null) {
                return Optional.empty();
            }
            List<String> ids = response.data().stream()
                    .map(AnthropicModel::id)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String effectiveBase(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    record AnthropicModelsResponse(List<AnthropicModel> data) {}
    record AnthropicModel(String id) {}
}
