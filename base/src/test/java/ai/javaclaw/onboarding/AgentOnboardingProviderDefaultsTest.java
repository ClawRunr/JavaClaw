package ai.javaclaw.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOnboardingProviderDefaultsTest {

    private final AgentOnboardingProvider provider = new AgentOnboardingProvider() {
        @Override public String getId() { return "test"; }
        @Override public String getLabel() { return "Test"; }
        @Override public String slogan() { return ""; }
        @Override public boolean requiresApiKey() { return false; }
    };

    @Test
    void availableModelsIsEmptyByDefault() {
        assertThat(provider.availableModels(null, "any-key")).isEmpty();
    }

    @Test
    void availableModelsAcceptsNullApiKey() {
        assertThat(provider.availableModels(null, null)).isEmpty();
    }
}
