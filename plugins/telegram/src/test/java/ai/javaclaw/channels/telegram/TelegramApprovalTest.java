package ai.javaclaw.channels.telegram;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a Telegram turn is approved in the same chat: the request arrives as a message,
 * {@code /approve <id>} runs the tool, and the answer follows. Real agent, real gate; only the
 * Telegram API is faked.
 */
class TelegramApprovalTest {

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<String> sent = new CopyOnWriteArrayList<>();
    TelegramChannel channel;

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedInTheChatAndTheAnswerFollows() throws Exception {
        channel = channel();

        channel.consume(update("owner", "fetch example.com"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String request = sent.getFirst();
        assertThat(request).contains(ChannelApprovalFixture.TOOL).contains("Risk: NETWORK").contains("example.com");
        assertThat(fixture.toolRuns()).isEmpty();

        // Arrives on the update thread while the turn is still waiting
        channel.consume(update("owner", "/approve " + shortId(request)));

        await(() -> sent.contains(ANSWER));
        assertThat(sent).anyMatch(m -> m.startsWith("Approved " + shortId(request)));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void anotherTelegramUserCannotApprove() throws Exception {
        channel = channel();
        channel.consume(update("owner", "fetch example.com"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String id = shortId(sent.getFirst());

        channel.consume(update("mallory", "/approve " + id));

        Thread.sleep(300);
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("telegram-42")).isNotEmpty();
        assertThat(sent).noneMatch(m -> m.startsWith("Approved"));

        channel.consume(update("owner", "/deny " + id));
        await(() -> sent.stream().anyMatch(m -> m.startsWith("Denied " + id)));
        assertThat(fixture.toolRuns()).isEmpty();
    }

    private TelegramChannel channel() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0, SendMessage.class).getText()
                    .replace("&quot;", "\"").replace("&amp;", "&"));
            return null;
        });
        return new TelegramChannel("token", "owner", client, fixture.approvals(), new ChannelRegistry());
    }

    private static Update update(String username, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(42L);
        when(message.getMessageThreadId()).thenReturn(null);
        when(message.getFrom()).thenReturn(user);
        when(user.getUserName()).thenReturn(username);
        return update;
    }
}
