package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.configuration.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.javaclaw.channels.whatsapp.WhatsAppOnboardingProvider.SESSION_ALLOWED_JID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppOnboardingProviderTest {

    @Mock
    Environment environment;

    @Mock
    ConfigurationManager configurationManager;

    private WhatsAppOnboardingProvider provider(boolean installed, boolean paired) {
        WhatsApp whatsApp = mock(WhatsApp.class);
        lenient().when(whatsApp.isInstalled()).thenReturn(installed);
        lenient().when(whatsApp.isPaired()).thenReturn(paired);
        return new WhatsAppOnboardingProvider(environment, whatsApp);
    }

    @Test
    void stepMetadataIsCorrect() {
        WhatsAppOnboardingProvider provider = provider(true, true);

        assertThat(provider.getStepId()).isEqualTo("whatsapp");
        assertThat(provider.getStepTitle()).isEqualTo("WhatsApp");
        assertThat(provider.getTemplatePath()).isEqualTo("onboarding/steps/whatsapp");
        assertThat(provider.isOptional()).isTrue();
    }

    @Test
    void processStepBlocksWhenWacliNotInstalled() {
        WhatsAppOnboardingProvider provider = provider(false, false);

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "1234567890@s.whatsapp.net"), new HashMap<>());

        assertThat(result).contains("wacli is not installed");
    }

    @Test
    void processStepRejectsInvalidJid() {
        WhatsAppOnboardingProvider provider = provider(true, true);

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "not-a-jid"), new HashMap<>());

        assertThat(result).contains("not a one-to-one WhatsApp chat");
    }

    @Test
    void processStepRejectsChatsWithMoreThanOnePersonInThem() {
        WhatsAppOnboardingProvider provider = provider(true, true);

        for (String manyPeople : List.of("120363@g.us", "123@newsletter", "123@broadcast")) {
            Map<String, Object> session = new HashMap<>();

            String result = provider.processStep(Map.of("whatsappAllowedChatJid", manyPeople), session);

            assertThat(result).as(manyPeople).contains("not a one-to-one WhatsApp chat");
            assertThat(session).as(manyPeople).doesNotContainKey(SESSION_ALLOWED_JID);
        }
    }

    @Test
    void processStepTakesTheJidOutOfALinePickedFromTheChatList() {
        WhatsAppOnboardingProvider provider = provider(true, true);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "Alice Smith — 95318997741682@lid"), session);

        assertThat(result).isNull();
        assertThat(session).containsEntry(SESSION_ALLOWED_JID, "95318997741682@lid");
    }

    @Test
    void processStepAsksForAChatWhenNothingWasChosen() {
        WhatsAppOnboardingProvider provider = provider(true, true);

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "  "), new HashMap<>());

        assertThat(result).contains("Choose the WhatsApp chat");
    }

    @Test
    void processStepAcceptsChatJidWithDeviceSuffix() {
        WhatsAppOnboardingProvider provider = provider(true, true);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "235137262432490_1@s.whatsapp.net"), session);

        assertThat(result).isNull();
        assertThat(session).containsEntry(SESSION_ALLOWED_JID, "235137262432490_1@s.whatsapp.net");
    }

    @Test
    void processStepBlocksWhenNotPairedButKeepsJid() {
        WhatsAppOnboardingProvider provider = provider(true, false);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", "1234567890@s.whatsapp.net"), session);

        assertThat(result).contains("not paired");
        assertThat(session).containsEntry(SESSION_ALLOWED_JID, "1234567890@s.whatsapp.net");
    }

    @Test
    void processStepStoresJidWhenInstalledAndPaired() {
        WhatsAppOnboardingProvider provider = provider(true, true);
        Map<String, Object> session = new HashMap<>();

        String result = provider.processStep(Map.of("whatsappAllowedChatJid", " 1234567890@s.whatsapp.net "), session);

        assertThat(result).isNull();
        assertThat(session).containsEntry(SESSION_ALLOWED_JID, "1234567890@s.whatsapp.net");
    }

    @Test
    void saveConfigurationWritesEnabledAndJid() throws Exception {
        WhatsAppOnboardingProvider provider = provider(true, true);
        Map<String, Object> session = Map.of(SESSION_ALLOWED_JID, "1234567890@s.whatsapp.net");

        provider.saveConfiguration(session, configurationManager);

        verify(configurationManager).updateProperties(Map.of(
                "agent.channels.whatsapp.enabled", true,
                "agent.channels.whatsapp.allowed-chat-jid", "1234567890@s.whatsapp.net"
        ));
    }

    @Test
    void saveConfigurationDoesNothingWhenJidMissing() throws Exception {
        WhatsAppOnboardingProvider provider = provider(true, true);

        provider.saveConfiguration(new HashMap<>(), configurationManager);

        verifyNoInteractions(configurationManager);
    }

    @Test
    void prepareModelReportsInstalledAndPairedFlags() {
        when(environment.getProperty("agent.channels.whatsapp.allowed-chat-jid", "")).thenReturn("");
        WhatsAppOnboardingProvider provider = provider(true, true);
        Map<String, Object> model = new HashMap<>();

        provider.prepareModel(new HashMap<>(), model);

        assertThat(model).containsEntry("wacliInstalled", true);
        assertThat(model).containsEntry("wacliPaired", true);
    }
}
