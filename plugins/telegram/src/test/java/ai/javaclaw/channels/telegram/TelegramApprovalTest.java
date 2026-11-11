package ai.javaclaw.channels.telegram;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a Telegram turn is approved in the same chat: the request arrives in HTML with
 * a one-tap {@code /approve_<id>}, the tap runs the tool, the request is edited to show the outcome,
 * and the answer follows. Real agent, real gate; only the Telegram API is faked.
 */
class TelegramApprovalTest {

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<SendMessage> sent = new CopyOnWriteArrayList<>();
    final List<EditMessageText> edits = new CopyOnWriteArrayList<>();
    final AtomicInteger messageIds = new AtomicInteger(100);
    final TelegramChannel channel = channel();

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedWithOneTapTheRequestIsEditedAndTheAnswerFollows() {
        channel.consume(update("owner", "fetch example.com"));
        await(() -> !sent.isEmpty());
        SendMessage request = sent.getFirst();
        String id = shortId(request.getText());
        assertThat(request.getParseMode()).isEqualTo(ParseMode.HTML);
        assertThat(request.getText())
                .startsWith("<b>JavaClaw wants to run</b> <code>" + ChannelApprovalFixture.TOOL + "</code>")
                .contains("<pre>  url: https://example.com</pre>")
                // Plain text, not inside <code>, so Telegram makes it a tappable command
                .endsWith("Reply /approve_" + id + " or /deny_" + id);
        assertThat(fixture.toolRuns()).isEmpty();

        // What a tap on the command sends, arriving while the turn still waits
        channel.consume(update("owner", "/approve_" + id));

        await(() -> sent.stream().anyMatch(m -> m.getText().equals(ANSWER)));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(edits).singleElement().satisfies(edit -> {
            assertThat(edit.getMessageId()).isEqualTo(101);
            assertThat(edit.getParseMode()).isEqualTo(ParseMode.HTML);
            assertThat(edit.getText()).contains("✅ <b>Approved</b>: running " + ChannelApprovalFixture.TOOL)
                    .doesNotContain("/approve_").doesNotContain("Expires at");
        });
        // The request and the answer; the outcome went into the request
        assertThat(sent).hasSize(2);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void theOldSpaceSeparatedCommandStillWorks() {
        channel.consume(update("owner", "fetch example.com"));
        await(() -> !sent.isEmpty());

        channel.consume(update("owner", "/deny " + shortId(sent.getFirst().getText())));

        await(() -> !edits.isEmpty());
        assertThat(edits.getFirst().getText()).contains("❌ <b>Denied</b>");
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void anotherTelegramUserCannotApprove() throws Exception {
        channel.consume(update("owner", "fetch example.com"));
        await(() -> !sent.isEmpty());
        String id = shortId(sent.getFirst().getText());

        channel.consume(update("mallory", "/approve_" + id));

        Thread.sleep(300);
        assertThat(edits).isEmpty();
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("telegram-42")).isNotEmpty();

        channel.consume(update("owner", "/deny_" + id));
        await(() -> !edits.isEmpty());
        assertThat(fixture.toolRuns()).isEmpty();
    }

    @Test
    void aGroupChatGetsNoApprovalRequestAndTheCallIsDenied() {
        channel.consume(update("owner", "fetch example.com", false));

        await(() -> !sent.isEmpty());
        assertThat(sent).singleElement().satisfies(message -> assertThat(message.getText())
                .startsWith("Not done: Tool ").contains("was NOT run")
                .doesNotContain("/approve"));
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("telegram-42")).isEmpty();
    }

    private TelegramChannel channel() {
        TelegramClient client = mock(TelegramClient.class);
        try {
            when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> {
                SendMessage message = invocation.getArgument(0);
                sent.add(message);
                Message created = new Message();
                created.setMessageId(messageIds.incrementAndGet());
                return created;
            });
            when(client.execute(any(EditMessageText.class))).thenAnswer(invocation -> {
                edits.add(invocation.getArgument(0));
                return true;
            });
        } catch (TelegramApiException e) {
            throw new AssertionError(e);
        }
        return new TelegramChannel("token", "owner", client, fixture.approvals(), new ChannelRegistry());
    }

    private static Update update(String username, String text) {
        return update(username, text, true);
    }

    /** @param privateChat a one-to-one chat with the bot; otherwise a group */
    private static Update update(String username, String text, boolean privateChat) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(42L);
        when(message.isUserMessage()).thenReturn(privateChat);
        when(message.getMessageThreadId()).thenReturn(null);
        when(message.getFrom()).thenReturn(user);
        when(user.getUserName()).thenReturn(username);
        return update;
    }
}
