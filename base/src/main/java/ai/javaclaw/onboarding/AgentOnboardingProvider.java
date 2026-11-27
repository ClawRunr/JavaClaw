package ai.javaclaw.onboarding;

import java.util.List;
import java.util.Optional;

public interface AgentOnboardingProvider {

    String getId();

    String getLabel();

    String slogan();

    boolean requiresApiKey();

    String defaultModel();

    /** Whether the provider can be pointed at a custom endpoint (a proxy, gateway or self-hosted server). */
    default boolean supportsBaseUrl() {
        return true;
    }

    /**
     * Why this provider cannot be used on this machine right now (for example a required local tool
     * that is missing or not logged in), or empty when it is ready.
     */
    default Optional<String> readinessProblem() {
        return Optional.empty();
    }

    /** The models to choose from, or empty when the model is typed in freely. */
    default List<ModelChoice> models() {
        return List.of();
    }

    record ModelChoice(String id, String label) {
    }
}
