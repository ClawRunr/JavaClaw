package ai.javaclaw.channels.discord;

import ai.javaclaw.configuration.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordOnboardingProviderTest {

    @Mock
    Environment environment;

    @Mock
    ConfigurationManager configurationManager;

    @Test
    void stepMetadataIsCorrect() {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);

        assertThat(provider.getStepId()).isEqualTo("discord");
        assertThat(provider.getStepTitle()).isEqualTo("Discord");
        assertThat(provider.getTemplatePath()).isEqualTo("onboarding/steps/discord");
        assertThat(provider.isOptional()).isTrue();
    }

    @Test
    void processStepStoresNormalizedSessionValues() {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of(
                "discordToken", " bot-token ",
                "discordAllowedUser", "<@!123456789>"
        ), session);

        assertThat(result).isNull();
        assertThat(session).containsEntry(DiscordOnboardingProvider.SESSION_TOKEN, "bot-token");
        assertThat(session).containsEntry(DiscordOnboardingProvider.SESSION_ALLOWED_USER, "123456789");
    }

    @Test
    void processStepReturnsErrorWhenRequiredValueIsMissing() {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);

        String result = provider.processStep(Map.of(
                "discordToken", "",
                "discordAllowedUser", "123456789"
        ), new HashMap<>());

        assertThat(result).isEqualTo("Enter the Discord bot token to continue.");
    }

    @Test
    void prepareModelUsesSessionValuesWhenPresent() {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);
        Map<String, Object> session = Map.of(
                DiscordOnboardingProvider.SESSION_TOKEN, "session-token",
                DiscordOnboardingProvider.SESSION_ALLOWED_USER, "123456789"
        );
        Map<String, Object> model = new HashMap<>();

        provider.prepareModel(session, model);

        assertThat(model).containsEntry("discordToken", "session-token");
        assertThat(model).containsEntry("discordAllowedUser", "123456789");
    }

    @Test
    void prepareModelFallsBackToEnvironmentValues() {
        when(environment.getProperty("agent.channels.discord.token", "")).thenReturn("env-token");
        when(environment.getProperty("agent.channels.discord.allowed-user", "")).thenReturn("env-user");
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);
        Map<String, Object> model = new HashMap<>();

        provider.prepareModel(Map.of(), model);

        assertThat(model).containsEntry("discordToken", "env-token");
        assertThat(model).containsEntry("discordAllowedUser", "env-user");
    }

    @Test
    void saveConfigurationWritesAllProperties() throws IOException {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);
        Map<String, Object> session = Map.of(
                DiscordOnboardingProvider.SESSION_TOKEN, "token",
                DiscordOnboardingProvider.SESSION_ALLOWED_USER, "123456789"
        );

        provider.saveConfiguration(session, configurationManager);

        verify(configurationManager).updateProperties(Map.of(
                "agent.channels.discord.token", "token",
                "agent.channels.discord.allowed-user", "123456789"
        ));
    }

    @Test
    void saveConfigurationDoesNothingWhenSessionIsIncomplete() throws IOException {
        DiscordOnboardingProvider provider = new DiscordOnboardingProvider(environment);

        provider.saveConfiguration(Map.of(
                DiscordOnboardingProvider.SESSION_TOKEN, "token"
        ), configurationManager);

        verifyNoInteractions(configurationManager);
    }
}
