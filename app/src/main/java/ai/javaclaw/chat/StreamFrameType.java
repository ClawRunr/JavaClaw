package ai.javaclaw.chat;

/**
 * Type discriminator of the JSON frames streamed to the web chat WebSocket client.
 * Every frame carries its payload (if any) in a uniform {@code data} field.
 */
public enum StreamFrameType {
    CHUNK("chunk"),
    DONE("done"),
    ERROR("error");

    private final String type;

    StreamFrameType(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }
}
