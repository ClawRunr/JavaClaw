package ai.javaclaw.channels;

/**
 * The chat a messaging channel's turn came from, as {@link ChannelApprovals} needs it: where the
 * answer goes, and where approval requests are posted and later updated with their outcome.
 */
public interface ApprovalChat {

    /** The markup approval messages are written in for this channel. */
    ApprovalFormat format();

    /** Sends the agent's answer (or a short notice), formatted the way the channel formats any reply. */
    void send(String text);

    /**
     * Sends a message already written in {@link #format()}.
     *
     * @return what identifies the sent message for {@link #edit}, or {@code null} when it cannot be
     *         edited later
     */
    Object sendFormatted(String formatted);

    /**
     * Replaces a message sent with {@link #sendFormatted}.
     *
     * @return {@code false} when the channel cannot edit messages or the edit failed, so the caller
     *         sends a reply instead
     */
    default boolean edit(Object message, String formatted) {
        return false;
    }
}
