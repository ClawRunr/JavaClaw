package ai.javaclaw.channels.whatsapp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WacliWebhookPayloadTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsRealWacliWireFormat() throws Exception {
        String json = """
                {
                  "Chat": "1234567890@s.whatsapp.net",
                  "ID": "3EB0ABCDEF",
                  "SenderJID": "1234567890@s.whatsapp.net",
                  "Timestamp": "2024-01-03T00:00:00Z",
                  "FromMe": false,
                  "Text": "hello there",
                  "PushName": "Alice",
                  "Buttons": null,
                  "Media": null,
                  "IsForwarded": false,
                  "ForwardingScore": 0,
                  "Starred": false
                }
                """;

        WacliWebhookPayload payload = mapper.readValue(json, WacliWebhookPayload.class);

        assertThat(payload.chat()).isEqualTo("1234567890@s.whatsapp.net");
        assertThat(payload.senderJid()).isEqualTo("1234567890@s.whatsapp.net");
        assertThat(payload.fromMe()).isFalse();
        assertThat(payload.text()).isEqualTo("hello there");
        assertThat(payload.id()).isEqualTo("3EB0ABCDEF");
        assertThat(payload.pushName()).isEqualTo("Alice");
        assertThat(payload.timestamp()).isEqualTo("2024-01-03T00:00:00Z");
    }

    @Test
    void bindsFromMeTrue() throws Exception {
        String json = "{\"Chat\":\"1@s.whatsapp.net\",\"FromMe\":true,\"Text\":\"mine\"}";

        WacliWebhookPayload payload = mapper.readValue(json, WacliWebhookPayload.class);

        assertThat(payload.fromMe()).isTrue();
        assertThat(payload.chat()).isEqualTo("1@s.whatsapp.net");
    }
}
