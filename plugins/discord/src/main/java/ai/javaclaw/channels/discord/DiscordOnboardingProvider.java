package ai.javaclaw.channels.discord;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@Order(53)
public class DiscordOnboardingProvider implements OnboardingProvider {

    static final String SESSION_TOKEN = "onboarding.discord.token";
    static final String SESSION_ALLOWED_USER = "onboarding.discord.allowed-user";

    private static final String TOKEN_PROPERTY = "agent.channels.discord.token";
    private static final String ALLOWED_USER_PROPERTY = "agent.channels.discord.allowed-user";

    private final Environment env;

    public DiscordOnboardingProvider(Environment env) {
        this.env = env;
    }

    @Override
    public boolean isOptional() {return true;}

    @Override
    public String getStepId() {return "discord";}

    @Override
    public String getStepTitle() {return "Discord";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/discord";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        model.put("discordToken", session.getOrDefault(SESSION_TOKEN, env.getProperty(TOKEN_PROPERTY, "")));
        model.put("discordAllowedUser", session.getOrDefault(SESSION_ALLOWED_USER, env.getProperty(ALLOWED_USER_PROPERTY, "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String token = formParams.getOrDefault("discordToken", "").trim();
        String allowedUser = normalizeUserId(formParams.get("discordAllowedUser"));

        if (token.isBlank()) {
            return "Enter the Discord bot token to continue.";
        }
        if (allowedUser == null) {
            return "Enter the Discord user ID that should be allowed to use the bot.";
        }

        session.put(SESSION_TOKEN, token);
        session.put(SESSION_ALLOWED_USER, allowedUser);
        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String token = (String) session.get(SESSION_TOKEN);
        String allowedUser = (String) session.get(SESSION_ALLOWED_USER);

        if (token != null && allowedUser != null) {
            configurationManager.updateProperties(Map.of(
                    TOKEN_PROPERTY, token,
                    ALLOWED_USER_PROPERTY, allowedUser
            ));
        }
    }

    private static String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        String normalized = userId.trim();
        if (normalized.startsWith("<@") && normalized.endsWith(">")) {
            normalized = normalized.substring(2, normalized.length() - 1);
            if (normalized.startsWith("!")) {
                normalized = normalized.substring(1);
            }
        }
        return normalized.isBlank() ? null : normalized;
    }
}
