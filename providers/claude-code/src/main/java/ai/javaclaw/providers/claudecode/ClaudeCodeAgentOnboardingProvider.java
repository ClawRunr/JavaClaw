package ai.javaclaw.providers.claudecode;

import ai.javaclaw.onboarding.AgentOnboardingProvider;

import java.util.List;
import java.util.Optional;

public class ClaudeCodeAgentOnboardingProvider implements AgentOnboardingProvider {

    @Override
    public String getId() {
        return ClaudeCodeChatModelFactory.PROVIDER_TYPE;
    }

    @Override
    public String getLabel() {
        return "Claude subscription";
    }

    @Override
    public String slogan() {
        return "Uses your Claude Pro/Max plan through the official Claude Code CLI. No API key required";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public boolean supportsBaseUrl() {
        return false;
    }

    @Override
    public String defaultModel() {
        return ClaudeCodeChatModel.DEFAULT_MODEL;
    }

    @Override
    public List<ModelChoice> models() {
        return ClaudeCodeModels.CHOICES.entrySet().stream()
                .map(choice -> new ModelChoice(choice.getKey(), choice.getValue()))
                .toList();
    }

    /** Asks the CLI itself (never Anthropic) whether it is installed and logged in. */
    @Override
    public Optional<String> readinessProblem() {
        ClaudeCodeCli.Status status = ClaudeCodeCli.status();
        return status.available() && status.loggedIn() ? Optional.empty() : Optional.of(status.detail());
    }
}
