package ai.javaclaw.testsupport;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link ApprovalChat} that records what it is sent, in order. */
public final class RecordingChat implements ApprovalChat {

    private final ApprovalFormat format;
    private final boolean canAsk;
    private final List<String> sent = new CopyOnWriteArrayList<>();

    public RecordingChat(ApprovalFormat format) {
        this(format, true);
    }

    /** @param canAsk {@code false} for a chat others can read, such as a group */
    public RecordingChat(ApprovalFormat format, boolean canAsk) {
        this.format = format;
        this.canAsk = canAsk;
    }

    @Override
    public boolean canAsk() {
        return canAsk;
    }

    /** Every message sent, plain or formatted, in order. */
    public List<String> sent() {
        return sent;
    }

    @Override
    public ApprovalFormat format() {
        return format;
    }

    @Override
    public void send(String text) {
        sent.add(text);
    }

    @Override
    public void sendFormatted(String formatted) {
        sent.add(formatted);
    }
}
