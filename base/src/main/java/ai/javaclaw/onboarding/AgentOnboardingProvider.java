package ai.javaclaw.onboarding;

import java.util.Map;
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

    default String createPropertyKey(String propertySuffix) {
        return "spring.ai." + getId() + "." + propertySuffix;
    }

    default void saveProperty(Map<String, Object> properties, String propertySuffix, String value) {
        if (value == null || value.isBlank()) return;
        properties.put(createPropertyKey(propertySuffix), value);
    }

    record SystemWideToken(String name, String token) {}
}
