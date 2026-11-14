package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.channels.whatsapp.WhatsAppChannel.WhatsAppChannelMessageReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppChannelTest {

    private static final String ALLOWED_JID = "1234567890@s.whatsapp.net";

    @Mock
    private WhatsApp whatsApp;

    @Mock
    private Agent agent;

    @Mock
    private ChannelRegistry channelRegistry;

    private WhatsAppChannel channel;

    @BeforeEach
    void setUp() {
        channel = new WhatsAppChannel(whatsApp, channelRegistry, agent);
    }

    @Test
    void startRegistersItselfAsReceiverAndChannel() {
        when(whatsApp.start()).thenReturn(true);

        channel.start();

        verify(whatsApp).registerMessageReceiver(any());
        verify(channelRegistry).registerChannel(channel);
    }

    @Test
    void doesNotRegisterWhenWhatsAppCannotStart() {
        when(whatsApp.start()).thenReturn(false);

        channel.start();

        verify(whatsApp, never()).registerMessageReceiver(any());
        verifyNoInteractions(channelRegistry);
    }

    @Test
    void stopUnregistersChannelFromRegistry() {
        channel.stop();

        verify(whatsApp).stop();
        verify(channelRegistry).unregisterChannel(channel);
    }

    @Test
    void sendMessageGoesToWhatsApp() {
        channel.sendMessage("hi there");

        verify(whatsApp).sendMessage("hi there");
    }

    @Test
    void answersWhatTheAgentReplies() {
        when(agent.respondTo(AgentRequest.chat(ALLOWED_JID, SessionChannel.WHATSAPP, "hello"))).thenReturn("hi there");

        channel.onMessage(ALLOWED_JID, "hello");

        verify(whatsApp).sendMessage("hi there");
    }

    @Test
    void publishesAnEventNamingTheChatAsTheConversation() {
        when(agent.respondTo(AgentRequest.chat(ALLOWED_JID, SessionChannel.WHATSAPP, "hello"))).thenReturn("hi there");

        channel.onMessage(ALLOWED_JID, "hello");

        ArgumentCaptor<WhatsAppChannelMessageReceivedEvent> published =
                ArgumentCaptor.forClass(WhatsAppChannelMessageReceivedEvent.class);
        verify(channelRegistry).publishMessageReceivedEvent(published.capture());
        assertThat(published.getValue().getChannel()).isEqualTo(WhatsAppChannel.CHANNEL_ID);
        assertThat(published.getValue().getMessage()).isEqualTo("hello");
        assertThat(published.getValue().getConversationId()).isEqualTo(ALLOWED_JID);
    }

    @Test
    void publishesTheEventBeforeAskingTheAgent() {
        // The agent turn takes seconds; the registry has to know which channel is live before then,
        // or a task finishing mid-turn would have its reply routed somewhere else.
        when(agent.respondTo(AgentRequest.chat(ALLOWED_JID, SessionChannel.WHATSAPP, "hello"))).thenAnswer(invocation -> {
            verify(channelRegistry).publishMessageReceivedEvent(any());
            return "hi there";
        });

        channel.onMessage(ALLOWED_JID, "hello");

        verify(whatsApp).sendMessage("hi there");
    }

    @Test
    void swallowsAgentFailures() {
        when(agent.respondTo(AgentRequest.chat(ALLOWED_JID, SessionChannel.WHATSAPP, "hello"))).thenThrow(new RuntimeException("boom"));

        channel.onMessage(ALLOWED_JID, "hello");

        verify(whatsApp, never()).sendMessage(anyString());
    }
}
