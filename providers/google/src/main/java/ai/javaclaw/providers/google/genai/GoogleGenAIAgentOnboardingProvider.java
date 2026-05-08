package ai.javaclaw.providers.google.genai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.stereotype.Component;

@Component
public class GoogleGenAIAgentOnboardingProvider implements AgentOnboardingProvider {

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
    public String defaultModel() {
        return "gemini-3-flash-preview";
    }
}
