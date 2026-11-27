package ai.javaclaw.tools.brave;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@Order(56)
public class BraveOnboardingProvider implements OnboardingProvider {

    public static final String AGENT_BROWSER_BRAVE_API_KEY = "agent.browser.brave.api-key";

    static final String SESSION_API_KEY = "onboarding.brave.api-key";

    private final Environment env;

    public BraveOnboardingProvider(Environment env) {
        this.env = env;
    }

    @Override
    public boolean isOptional() {return true;}

    @Override
    public String getStepId() {return "brave";}

    @Override
    public String getStepTitle() {return "Brave Search";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/brave";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        model.put("braveApiKey", session.getOrDefault(SESSION_API_KEY, env.getProperty(AGENT_BROWSER_BRAVE_API_KEY, "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String apiKey = formParams.getOrDefault("braveApiKey", "").trim();
        if (!apiKey.isBlank()) {
            session.put(SESSION_API_KEY, apiKey);
        }
        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String apiKey = (String) session.get(SESSION_API_KEY);
        if (apiKey != null) {
            configurationManager.updateProperties(Map.of(AGENT_BROWSER_BRAVE_API_KEY, apiKey));
        }
    }
}
