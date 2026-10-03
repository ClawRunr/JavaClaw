package ai.javaclaw.channels.teams;

import ai.javaclaw.configuration.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamsOnboardingProviderTest {

    @Mock
    Environment environment;

    @Mock
    ConfigurationManager configurationManager;

    @Test
    void stepMetadataIsCorrect() {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);

        assertThat(provider.getStepId()).isEqualTo("teams");
        assertThat(provider.getStepTitle()).isEqualTo("Microsoft Teams");
        assertThat(provider.getTemplatePath()).isEqualTo("onboarding/steps/teams");
        assertThat(provider.isOptional()).isTrue();
    }

    @Test
    void processStepStoresTrimmedSessionValues() {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of(
                "teamsAppId", " app-1 ",
                "teamsAppSecret", " secret-1 ",
                "teamsTenantId", " tenant-1 "
        ), session);

        assertThat(result).isNull();
        assertThat(session).containsEntry(TeamsOnboardingProvider.SESSION_APP_ID, "app-1");
        assertThat(session).containsEntry(TeamsOnboardingProvider.SESSION_APP_SECRET, "secret-1");
        assertThat(session).containsEntry(TeamsOnboardingProvider.SESSION_TENANT_ID, "tenant-1");
    }

    @Test
    void processStepReturnsErrorWhenAnyFieldIsMissing() {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);

        String result = provider.processStep(Map.of(
                "teamsAppId", "app-1",
                "teamsAppSecret", "",
                "teamsTenantId", "tenant-1"
        ), new HashMap<>());

        assertThat(result).isEqualTo("App ID, App Secret and Tenant ID are all required.");
    }

    @Test
    void prepareModelUsesSessionValuesWhenPresentAndNeverExposesSecret() {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);
        Map<String, Object> session = Map.of(
                TeamsOnboardingProvider.SESSION_APP_ID, "session-app-id",
                TeamsOnboardingProvider.SESSION_APP_SECRET, "session-secret",
                TeamsOnboardingProvider.SESSION_TENANT_ID, "session-tenant-id"
        );
        Map<String, Object> model = new HashMap<>();

        provider.prepareModel(session, model);

        assertThat(model).containsEntry("teamsAppId", "session-app-id");
        assertThat(model).containsEntry("teamsTenantId", "session-tenant-id");
        assertThat(model).doesNotContainKey("teamsAppSecret");
    }

    @Test
    void prepareModelFallsBackToEnvironmentValues() {
        when(environment.getProperty("agent.channels.teams.app-id", "")).thenReturn("env-app-id");
        when(environment.getProperty("agent.channels.teams.tenant-id", "")).thenReturn("env-tenant-id");
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);
        Map<String, Object> model = new HashMap<>();

        provider.prepareModel(Map.of(), model);

        assertThat(model).containsEntry("teamsAppId", "env-app-id");
        assertThat(model).containsEntry("teamsTenantId", "env-tenant-id");
    }

    @Test
    void saveConfigurationWritesAllPropertiesIncludingEnabledFlag() throws Exception {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);
        Map<String, Object> session = Map.of(
                TeamsOnboardingProvider.SESSION_APP_ID, "app-1",
                TeamsOnboardingProvider.SESSION_APP_SECRET, "secret-1",
                TeamsOnboardingProvider.SESSION_TENANT_ID, "tenant-1"
        );

        provider.saveConfiguration(session, configurationManager);

        verify(configurationManager).updateProperties(Map.of(
                "agent.channels.teams.enabled", true,
                "agent.channels.teams.app-id", "app-1",
                "agent.channels.teams.app-secret", "secret-1",
                "agent.channels.teams.tenant-id", "tenant-1"
        ));
    }

    @Test
    void saveConfigurationDoesNothingWhenAppIdMissing() throws Exception {
        TeamsOnboardingProvider provider = new TeamsOnboardingProvider(environment);

        provider.saveConfiguration(new HashMap<>(), configurationManager);

        verifyNoInteractions(configurationManager);
    }
}