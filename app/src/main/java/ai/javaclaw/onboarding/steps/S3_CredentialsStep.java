package ai.javaclaw.onboarding.steps;

import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProvider.ModelChoice;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(30)
public class S3_CredentialsStep implements OnboardingProvider {

    private final Environment env;
    private final AgentOnboardingProviders agentOnboardingProviders;

    public S3_CredentialsStep(Environment env, AgentOnboardingProviders agentOnboardingProviders) {
        this.env = env;
        this.agentOnboardingProviders = agentOnboardingProviders;
    }

    @Override
    public String getStepId() {return "credentials";}

    @Override
    public String getStepTitle() {return "Credentials";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/S3-credentials";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        String providerId = (String) session.getOrDefault(S2_ProviderStep.SESSION_PROVIDER, env.getProperty("agent.llm.providers.default.provider", ""));
        AgentOnboardingProvider provider = agentOnboardingProviders.findById(providerId).orElse(null);
        if (provider == null) return;

        // Onboarding configures the single "default" provider under agent.llm.providers.default.
        String base = "agent.llm.providers." + LlmProviderProperties.DEFAULT_PROVIDER_NAME;
        String baseUrlKey = base + ".base-url";
        String apiKeyKey = base + ".api-key";
        String modelKey = base + ".model";

        String currentModel = (String) session.get(S2_ProviderStep.SESSION_MODEL);
        String existingBaseUrl = env.getProperty(baseUrlKey, "");
        String existingApiKey = env.getProperty(apiKeyKey, "");
        String existingModel = env.getProperty(modelKey, "");
        model.put("selectedProvider", provider.getId());
        model.put("providerLabel", provider.getLabel());
        model.put("baseUrlPropertyKey", baseUrlKey);
        model.put("providerApiPropertyKey", apiKeyKey);
        model.put("chatModelPropertyKey", modelKey);
        model.put("requiresApiKey", provider.requiresApiKey());
        model.put("supportsBaseUrl", provider.supportsBaseUrl());
        model.put("baseUrl", session.getOrDefault(S2_ProviderStep.SESSION_BASE_URL, existingBaseUrl));
        model.put("apiKey", session.getOrDefault(S2_ProviderStep.SESSION_API_KEY, existingApiKey));
        String selectedModel = currentModel != null && !currentModel.isBlank() ? currentModel : (!existingModel.isBlank() ? existingModel : provider.defaultModel());
        model.put("model", selectedModel);
        model.put("modelChoices", modelChoices(provider, selectedModel));
        provider.readinessProblem().ifPresent(problem -> model.put("readinessProblem", problem));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String providerId = (String) session.getOrDefault(S2_ProviderStep.SESSION_PROVIDER, env.getProperty("agent.llm.providers.default.provider", ""));
        AgentOnboardingProvider provider = agentOnboardingProviders.findById(providerId).orElse(null);
        if (provider == null) {
            return "Provider selection is missing. Please go back and select a provider.";
        }

        String baseUrl = provider.supportsBaseUrl() ? formParams.getOrDefault("baseUrl", "").trim() : "";
        String apiKey = formParams.getOrDefault("apiKey", "").trim();
        String model = formParams.getOrDefault("model", "").trim();

        if (model.isBlank()) {
            return "Enter a model to continue.";
        }

        if (provider.requiresApiKey() && apiKey.isBlank()) {
            return "Enter an API key to continue.";
        }

        String readinessProblem = provider.readinessProblem().orElse(null);
        if (readinessProblem != null) {
            return readinessProblem;
        }

        session.put(S2_ProviderStep.SESSION_BASE_URL, baseUrl);
        session.put(S2_ProviderStep.SESSION_API_KEY, apiKey);
        session.put(S2_ProviderStep.SESSION_MODEL, model);
        return null;
    }

    /** The provider's model list; a configured model that is not on it stays selectable so it is never silently replaced. */
    private static List<ModelChoice> modelChoices(AgentOnboardingProvider provider, String selectedModel) {
        List<ModelChoice> choices = new ArrayList<>(provider.models());
        if (!choices.isEmpty() && choices.stream().noneMatch(choice -> choice.id().equals(selectedModel))) {
            choices.addFirst(new ModelChoice(selectedModel, selectedModel));
        }
        return choices;
    }

}
