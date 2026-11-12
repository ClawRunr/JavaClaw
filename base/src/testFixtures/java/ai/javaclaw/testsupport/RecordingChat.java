package ai.javaclaw.testsupport;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link ApprovalChat} that records what it is sent, in order. */
public final class RecordingChat implements ApprovalChat {

    private final ApprovalFormat format;
    private final List<String> sent = new CopyOnWriteArrayList<>();

    public RecordingChat(ApprovalFormat format) {
        this.format = format;
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
