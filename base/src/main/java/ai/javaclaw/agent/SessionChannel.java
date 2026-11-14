package ai.javaclaw.agent;

/**
 * The channel a session came from, stored in its metadata under {@link #ATTRIBUTE} next to its
 * {@link SessionKind}.
 */
public enum SessionChannel {

    /** The web chat, at {@code /chat} (HTMX) and {@code /ui} (Vaadin); both continue the same conversation. */
    WEB("web"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    WHATSAPP("whatsapp"),
    TEAMS("teams"),

    /** No channel: the session was started by JavaClaw itself, such as a scheduled task's. */
    INTERNAL("internal");

    /** The session metadata attribute that holds the channel. */
    public static final String ATTRIBUTE = "channel";

    private final String value;

    SessionChannel(String value) {
        this.value = value;
    }

    /** The value stored in the session's metadata. */
    public String value() {
        return value;
    }
}
