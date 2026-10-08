package ai.javaclaw.channels.teams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamsActivityPayload(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("serviceUrl") String serviceUrl,
        @JsonProperty("channelId") String channelId,
        @JsonProperty("text") String text,
        @JsonProperty("from") From from,
        @JsonProperty("conversation") Conversation conversation) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record From(@JsonProperty("id") String id, @JsonProperty("name") String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conversation(@JsonProperty("id") String id) {
    }
}