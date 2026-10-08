package ai.javaclaw.channels.teams;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TeamsActivityPayloadTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsRealBotFrameworkActivityFormat() {
        String json = """
                {
                  "type": "message",
                  "id": "abc123",
                  "serviceUrl": "https://smba.trafficmanager.net/amer/",
                  "channelId": "msteams",
                  "text": "hello there",
                  "from": { "id": "29:user-object-id", "name": "Alice" },
                  "conversation": { "id": "19:conv-id@thread.v2" },
                  "recipient": { "id": "28:bot-id" }
                }
                """;

        TeamsActivityPayload payload = mapper.readValue(json, TeamsActivityPayload.class);

        assertThat(payload.type()).isEqualTo("message");
        assertThat(payload.text()).isEqualTo("hello there");
        assertThat(payload.serviceUrl()).isEqualTo("https://smba.trafficmanager.net/amer/");
        assertThat(payload.from().id()).isEqualTo("29:user-object-id");
        assertThat(payload.conversation().id()).isEqualTo("19:conv-id@thread.v2");
    }
}