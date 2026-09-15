package ai.javaclaw.onboarding;

import java.util.List;
import java.util.Optional;

public interface AgentOnboardingProvider {

    String getId();

    String getLabel();

    String slogan();

    boolean requiresApiKey();

    /**
     * Live model names this provider offers, if discoverable.
     * <p>
     * {@code baseUrl} is the endpoint configured by the user (e.g. a proxy or self-hosted server);
     * when blank the provider falls back to its own default. {@code apiKey} may be {@code null}
     * for keyless providers (Ollama).
     * Return {@link Optional#empty()} when listing is unsupported (the model field stays a plain text input).
     * Never throw — callers rely on a graceful empty result.
     */
    default Optional<List<String>> availableModels(String baseUrl, String apiKey) {
        return Optional.empty();
    }

    default Optional<SystemWideToken> systemWideToken() {
        return Optional.empty();
    }

    record SystemWideToken(String name, String token) {}
}
