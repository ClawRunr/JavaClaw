package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final String ALLOWED_JID = "1234567890@s.whatsapp.net";

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private Agent agent;

    @Mock
    private WacliWhatsAppChannel channel;

    private WhatsAppWebhookController controller() {
        return controller(Runnable::run);
    }

    private WhatsAppWebhookController controller(Executor executor) {
        WacliProperties properties = new WacliProperties();
        properties.setEnabled(true);
        properties.setAllowedChatJid(ALLOWED_JID);
        return new WhatsAppWebhookController(properties, channelRegistry, agent, channel, executor);
    }

    private static WacliWebhookPayload payload(String chat, String senderJid, boolean fromMe, String text) {
        return new WacliWebhookPayload(chat, "msg-id", senderJid, fromMe, text, "Tester", "2024-01-03T00:00:00Z");
    }

    @Test
    void ignoresMessagesFromUnauthorizedChat() {
        WhatsAppWebhookController controller = controller();

        ResponseEntity<Void> response = controller.webhook(
                payload("999@s.whatsapp.net", "5555@s.whatsapp.net", false, "hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channelRegistry);
        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void ignoresEchoesOfRepliesTheAssistantSent() {
        WhatsAppWebhookController controller = controller();
        when(channel.wasSentByAgent("msg-id")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, ALLOWED_JID, true, "hi there"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channelRegistry);
        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void handlesYourOwnTypedMessageInSelfChat() {
        // A message you type yourself in "Message Yourself" also carries FromMe=true, but is not an
        // echo of an assistant reply, so it must still be handled -- this is the single-phone path.
        WhatsAppWebhookController controller = controller();
        when(channel.getName()).thenReturn("whatsapp");
        when(agent.respondTo(ALLOWED_JID, "remind me at 6")).thenReturn("Reminder set");

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, ALLOWED_JID, true, "remind me at 6"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(agent).respondTo(ALLOWED_JID, "remind me at 6");
        verify(channel).sendMessage("Reminder set");
    }

    @Test
    void ignoresMessagesWithBlankText() {
        WhatsAppWebhookController controller = controller();

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, ALLOWED_JID, false, "   "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channelRegistry);
        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void firesEventAndRepliesForAuthorizedChat() {
        WhatsAppWebhookController controller = controller();
        when(channel.getName()).thenReturn("whatsapp");
        when(agent.respondTo(eq(ALLOWED_JID), eq("hello"))).thenReturn("hi there");

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, "someone-else@s.whatsapp.net", false, "hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(channelRegistry).publishMessageReceivedEvent(argThat(event ->
                event instanceof WhatsAppChannelMessageReceivedEvent whatsAppEvent
                        && ALLOWED_JID.equals(whatsAppEvent.getConversationId())
                        && "hello".equals(whatsAppEvent.getMessage())
                        && "whatsapp".equals(whatsAppEvent.getChannel())));
        verify(agent).respondTo(ALLOWED_JID, "hello");
        verify(channel).sendMessage("hi there");
    }

    @Test
    void returnsOkForEmptyBody() {
        WhatsAppWebhookController controller = controller();

        ResponseEntity<Void> response = controller.webhook(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(agent, channelRegistry, channel);
    }

    @Test
    void respondsImmediatelyAndDispatchesAgentTurnToExecutor() {
        List<Runnable> deferred = new ArrayList<>();
        WhatsAppWebhookController controller = controller(deferred::add);
        when(channel.getName()).thenReturn("whatsapp");

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, ALLOWED_JID, false, "hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(channelRegistry).publishMessageReceivedEvent(any());
        verifyNoInteractions(agent);
        assertThat(deferred).hasSize(1);

        when(agent.respondTo(ALLOWED_JID, "hello")).thenReturn("hi there");
        deferred.get(0).run();
        verify(agent).respondTo(ALLOWED_JID, "hello");
        verify(channel).sendMessage("hi there");
    }

    @Test
    void swallowsAgentFailureInWorker() {
        WhatsAppWebhookController controller = controller();
        when(channel.getName()).thenReturn("whatsapp");
        when(agent.respondTo(ALLOWED_JID, "hello")).thenThrow(new RuntimeException("boom"));

        ResponseEntity<Void> response = controller.webhook(
                payload(ALLOWED_JID, ALLOWED_JID, false, "hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(agent).respondTo(ALLOWED_JID, "hello");
        verify(channel, never()).sendMessage(anyString());
    }
}
