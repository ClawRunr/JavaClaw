package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;

import java.util.Map;

public class OpenAIAgentOnboardingProvider implements AgentOnboardingProvider {

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
    public String defaultModel() {
        return "gpt-5.4";
    }

    @Override
    public Map<String, Object> additionalProperties() {
        return Map.of("spring.ai.openai.base-url", "https://api.openai.com");
    }
}
