package ai.javaclaw.providers.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static ai.javaclaw.providers.anthropic.AnthropticClaudeCodeConfiguration.CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER;

@Component
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
        return "Uses your existing Claude Code or Anthropic API-key for Claude-based chat";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String defaultModel() {
        return "claude-sonnet-4-6";
    }

    @Override
    public Optional<SystemWideToken> systemWideToken() {
        Optional<String> token = AnthropicClaudeCodeOAuthTokenExtractor.getToken();
        if (token.isEmpty()) return Optional.empty();

        return Optional.of(new SystemWideToken("Claude Code", CLAUDE_CODE_OATH_TOKEN_PLACEHOLDER));
    }
}
