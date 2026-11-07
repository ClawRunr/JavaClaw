package ai.javaclaw.testsupport;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link ApprovalChat} that records what it is sent, in order, and keeps every sent message by
 * id so edits can be checked; editing can be switched off to stand in for a channel that cannot.
 */
public final class RecordingChat implements ApprovalChat {

    private final ApprovalFormat format;
    private final boolean editable;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> messages = new ConcurrentHashMap<>();
    private final List<String> edits = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    public RecordingChat(ApprovalFormat format, boolean editable) {
        this.format = format;
        this.editable = editable;
    }

    /** Every message sent, plain or formatted, in order (edits are not new messages). */
    public List<String> sent() {
        return sent;
    }

    /** The text of every edit, in order. */
    public List<String> edits() {
        return edits;
    }

    /** The current text of the {@code index}-th message sent with {@link #sendFormatted}, after any edits. */
    public String formatted(int index) {
        return messages.get(index + 1);
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
    public Object sendFormatted(String formatted) {
        sent.add(formatted);
        int id = ids.incrementAndGet();
        messages.put(id, formatted);
        return editable ? id : null;
    }

    @Override
    public boolean edit(Object message, String formatted) {
        if (!editable || !(message instanceof Integer id) || !messages.containsKey(id)) {
            return false;
        }
        messages.put(id, formatted);
        edits.add(formatted);
        return true;
    }
}
