package ai.javaclaw.channels.telegram;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@Order(50)
public class TelegramOnboardingProvider implements OnboardingProvider {

    static final String SESSION_TOKEN = "onboarding.telegram.token";
    static final String SESSION_USERNAME = "onboarding.telegram.username";

    private final Environment env;

    public TelegramOnboardingProvider(Environment env) {
        this.env = env;
    }

    @Override
    public boolean isOptional() {return true;}

    @Override
    public String getStepId() {return "telegram";}

    @Override
    public String getStepTitle() {return "Telegram";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/telegram";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        model.put("telegramUsername", session.getOrDefault(SESSION_USERNAME, env.getProperty("agent.channels.telegram.username", "")));
        model.put("telegramToken", session.getOrDefault(SESSION_TOKEN, env.getProperty("agent.channels.telegram.token", "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String token = formParams.getOrDefault("telegramBotToken", "").trim();
        String username = TelegramUsernames.normalize(formParams.get("telegramUsername"));

        if (username != null) {
            session.put(SESSION_USERNAME, username);
        }

        if (token.isBlank()) {
            return "Enter the Telegram bot token to continue.";
        }
        if (username == null) {
            return "Enter the Telegram username that should be allowed to use the bot.";
        }

        session.put(SESSION_TOKEN, token);
        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String token = (String) session.get(SESSION_TOKEN);
        String username = (String) session.get(SESSION_USERNAME);
        if (token != null && username != null) {
            configurationManager.updateProperties(Map.of(
                    "agent.channels.telegram.token", token,
                    "agent.channels.telegram.username", username
            ));
        }
    }
}
