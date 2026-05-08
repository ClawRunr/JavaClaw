package ai.javaclaw.providers.ollama;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.stereotype.Component;

@Component
public class OllamaAgentOnboardingProvider implements AgentOnboardingProvider {

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
    public String defaultModel() {
        return "qwen3.5:27b";
    }
}
