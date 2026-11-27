package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;

public class AnthropicAgentOnboardingProvider implements AgentOnboardingProvider {
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
        return "Uses an Anthropic API key for Claude-based chat";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String defaultModel() {
        return "claude-sonnet-4-6";
    }
}
