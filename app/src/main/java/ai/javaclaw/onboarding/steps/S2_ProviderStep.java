package ai.javaclaw.onboarding.steps;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(20)
public class S2_ProviderStep implements OnboardingProvider {

    static final String SESSION_PROVIDER = "onboarding.provider";
    static final String SESSION_MODEL = "onboarding.model";
    static final String SESSION_API_KEY = "onboarding.apiKey";

    private final AgentOnboardingProviders agentOnboardingProviders;
    private final Environment env;

    public S2_ProviderStep(AgentOnboardingProviders agentOnboardingProviders, Environment env) {
        this.agentOnboardingProviders = agentOnboardingProviders;
        this.env = env;
    }

    @Override
    public String getStepId() {return "provider";}

    @Override
    public String getStepTitle() {return "Provider";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/S2-provider";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        model.put("providers", agentOnboardingProviders.getAll());
        model.put("selectedProvider", session.getOrDefault(SESSION_PROVIDER, env.getProperty("spring.ai.model.chat", "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String providerId = formParams.get("provider");
        if (providerId == null || providerId.isBlank()) {
            return "Choose one of the supported providers to continue.";
        }
        AgentOnboardingProvider agentOnboardingProvider = agentOnboardingProviders.getById(providerId);
        // Clear downstream session state when provider changes
        String currentProvider = (String) session.get(SESSION_PROVIDER);
        if (!agentOnboardingProvider.getId().equals(currentProvider)) {
            session.remove(SESSION_MODEL);
            session.remove(SESSION_API_KEY);
        }
        session.put(SESSION_PROVIDER, agentOnboardingProvider.getId());
        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String providerId = (String) session.get(SESSION_PROVIDER);
        String model = (String) session.get(SESSION_MODEL);
        String apiKey = (String) session.getOrDefault(SESSION_API_KEY, "");

        AgentOnboardingProvider agentOnboardingProvider = agentOnboardingProviders.getById(providerId);
        Map<String, Object> props = new LinkedHashMap<>();
        agentOnboardingProvider.saveProperty(props, "chat.options.model", model);
        agentOnboardingProvider.saveProperty(props, "api-key", apiKey);
        props.put("spring.ai.model.chat", agentOnboardingProvider.getId().replace(".", "-"));
        configurationManager.updateProperties(props);
    }
}
