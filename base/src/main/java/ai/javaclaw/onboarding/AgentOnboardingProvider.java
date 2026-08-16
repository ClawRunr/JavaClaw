package ai.javaclaw.onboarding;

import java.util.Optional;

public interface AgentOnboardingProvider {

    String getId();

    String getLabel();

    String slogan();

    boolean requiresApiKey();

    String defaultModel();

    default Optional<SystemWideToken> systemWideToken() {
        return Optional.empty();
    }

    record SystemWideToken(String name, String token) {}
}
