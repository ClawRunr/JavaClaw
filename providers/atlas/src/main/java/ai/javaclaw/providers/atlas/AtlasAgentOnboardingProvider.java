package ai.javaclaw.providers.atlas;

import ai.javaclaw.onboarding.AgentOnboardingProvider;

import java.util.Map;

public class AtlasAgentOnboardingProvider implements AgentOnboardingProvider {

    @Override
    public String getId() {
        return "atlas";
    }

    @Override
    public String getLabel() {
        return "Atlas Cloud";
    }

    @Override
    public String slogan() {
        return "Use Atlas Cloud's OpenAI-compatible API as an agent.";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String defaultModel() {
        return "qwen/qwen3.8-max";
    }

    @Override
    public String chatModelId() {
        return "openai";
    }

    @Override
    public String createPropertyKey(String propertySuffix) {
        return "spring.ai.openai." + propertySuffix;
    }

    @Override
    public Map<String, Object> additionalProperties() {
        return Map.of("spring.ai.openai.base-url", "https://api.atlascloud.ai");
    }
}
