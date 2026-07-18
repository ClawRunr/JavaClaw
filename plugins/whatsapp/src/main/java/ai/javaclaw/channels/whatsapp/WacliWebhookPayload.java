package ai.javaclaw.channels.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WacliWebhookPayload(
        @JsonProperty("Chat") String chat,
        @JsonProperty("ID") String id,
        @JsonProperty("SenderJID") String senderJid,
        @JsonProperty("FromMe") boolean fromMe,
        @JsonProperty("Text") String text,
        @JsonProperty("PushName") String pushName,
        @JsonProperty("Timestamp") String timestamp) {
}
