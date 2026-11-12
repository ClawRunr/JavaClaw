package ai.javaclaw.channels;

/**
 * The chat a messaging channel's turn came from, as {@link ChannelApprovals} needs it: where the
 * answer goes, and where approval requests and their outcomes are posted.
 */
public interface ApprovalChat {

    /**
     * Whether the owner can be asked here. A chat other people can read, such as a group, says no,
     * so calls that need approval are refused instead of being asked about in public.
     */
    default boolean canAsk() {
        return true;
    }

    /** The markup approval messages are written in for this channel. */
    ApprovalFormat format();

    /** Sends the agent's answer (or a short notice), formatted the way the channel formats any reply. */
    void send(String text);

    /** Sends a message already written in {@link #format()}. */
    void sendFormatted(String formatted);
}
