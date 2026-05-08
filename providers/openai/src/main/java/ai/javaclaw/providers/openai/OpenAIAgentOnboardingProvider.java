package ai.javaclaw.providers.openai;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.stereotype.Component;

@Component
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
}
