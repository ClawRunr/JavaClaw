package ai.javaclaw.channels.whatsapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookControllerTest {

    private static final WacliWebhookPayload PAYLOAD = new WacliWebhookPayload(
            "1234567890@s.whatsapp.net", "msg-id", "1234567890@s.whatsapp.net",
            false, "hello", "Tester", "2024-01-03T00:00:00Z");

    private static final long WAIT_FOR_BACKGROUND_WORK_MILLIS = 2_000;

    @Mock
    private WhatsApp whatsApp;

    @Test
    void handsTheMessageToWhatsApp() {
        ResponseEntity<Void> response = new WhatsAppWebhookController(whatsApp).webhook(PAYLOAD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(whatsApp, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).onIncomingMessage(PAYLOAD);
    }

    @Test
    void acceptsAnEmptyBody() {
        ResponseEntity<Void> response = new WhatsAppWebhookController(whatsApp).webhook(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(whatsApp, timeout(WAIT_FOR_BACKGROUND_WORK_MILLIS)).onIncomingMessage(null);
    }
}
