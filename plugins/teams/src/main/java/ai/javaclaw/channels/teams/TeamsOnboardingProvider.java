package ai.javaclaw.channels.teams;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(57)
public class TeamsOnboardingProvider implements OnboardingProvider {

    static final String SESSION_APP_ID = "onboarding.teams.app-id";
    static final String SESSION_APP_SECRET = "onboarding.teams.app-secret";
    static final String SESSION_TENANT_ID = "onboarding.teams.tenant-id";

    private static final String ENABLED_PROPERTY = "agent.channels.teams.enabled";
    private static final String APP_ID_PROPERTY = "agent.channels.teams.app-id";
    private static final String APP_SECRET_PROPERTY = "agent.channels.teams.app-secret";
    private static final String TENANT_ID_PROPERTY = "agent.channels.teams.tenant-id";

    private final Environment env;

    public TeamsOnboardingProvider(Environment env) {
        this.env = env;
    }

    @Override
    public boolean isOptional() { return true; }

    @Override
    public String getStepId() { return "teams"; }

    @Override
    public String getStepTitle() { return "Microsoft Teams"; }

    @Override
    public String getTemplatePath() { return "onboarding/steps/teams"; }

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        model.put("teamsAppId", session.getOrDefault(SESSION_APP_ID, env.getProperty(APP_ID_PROPERTY, "")));
        model.put("teamsTenantId", session.getOrDefault(SESSION_TENANT_ID, env.getProperty(TENANT_ID_PROPERTY, "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String appId = trimToNull(formParams.get("teamsAppId"));
        String appSecret = trimToNull(formParams.get("teamsAppSecret"));
        String tenantId = trimToNull(formParams.get("teamsTenantId"));

        if (appId == null || appSecret == null || tenantId == null) {
            return "App ID, App Secret and Tenant ID are all required.";
        }

        session.put(SESSION_APP_ID, appId);
        session.put(SESSION_APP_SECRET, appSecret);
        session.put(SESSION_TENANT_ID, tenantId);
        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String appId = (String) session.get(SESSION_APP_ID);
        if (appId == null) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ENABLED_PROPERTY, true);
        properties.put(APP_ID_PROPERTY, appId);
        properties.put(APP_SECRET_PROPERTY, session.get(SESSION_APP_SECRET));
        properties.put(TENANT_ID_PROPERTY, session.get(SESSION_TENANT_ID));
        configurationManager.updateProperties(properties);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}