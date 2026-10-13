package ai.javaclaw.channels.telegram;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramChannelTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Agent agent;

    // -----------------------------------------------------------------------
    // Ignored updates
    // -----------------------------------------------------------------------

    @Test
    void ignoresUpdatesWithoutMessage() {
        TelegramChannel channel = channel("allowed_user");
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        channel.consume(update);

        verifyNoInteractions(agent, telegramClient);
    }

    @Test
    void ignoresUpdatesWithoutText() {
        TelegramChannel channel = channel("allowed_user");
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(false);

        channel.consume(update);

        verifyNoInteractions(agent, telegramClient);
    }

    @Test
    void ignoresMessagesFromNullUsername() {
        TelegramChannel channel = channel("allowed_user");
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getFrom()).thenReturn(null);

        channel.consume(update);

        verifyNoInteractions(agent, telegramClient);
    }

    @Test
    void ignoresMessagesFromUnauthorizedUser() {
        TelegramChannel channel = channel("allowed_user");

        channel.consume(updateFromUnknownUser("other_user"));

        verify(agent, never()).respondTo(anyString(), anyString());
        verifyNoInteractions(telegramClient);
    }

    // -----------------------------------------------------------------------
    // Username matching
    // -----------------------------------------------------------------------

    @Test
    void usernameMatchingIsCaseInsensitive() {
        TelegramChannel channel = channel("Allowed_User");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(agent).respondTo(anyString(), anyString());
    }

    @Test
    void stripsLeadingAtFromConfiguredUsername() {
        TelegramChannel channel = channel("@Allowed_User");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(agent).respondTo(anyString(), anyString());
    }

    @Test
    void stripsLeadingAtFromIncomingUsername() {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("@allowed_user", "hello", 42L, null));

        verify(agent).respondTo(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Conversation ID and SendMessage
    // -----------------------------------------------------------------------

    @Test
    void usesChannelChatIdAsConversationId() {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(agent).respondTo(eq("telegram-42"), eq("hello"));
    }

    @Test
    void includesMessageThreadIdInConversationId() {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, 567));

        verify(agent).respondTo(eq("telegram-42-567"), eq("hello"));
    }

    @Test
    void sendsAgentResponseToCorrectChatId() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                "42".equals(msg.getChatId()) && "hi".equals(msg.getText())));
    }

    @Test
    void passesMessageThreadIdToSendMessage() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, 567));

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                Integer.valueOf(567).equals(msg.getMessageThreadId())));
    }

    @Test
    void doesNotSetMessageThreadIdWhenAbsent() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                msg.getMessageThreadId() == null));
    }

    // -----------------------------------------------------------------------
    // sendMessage fallback
    // -----------------------------------------------------------------------

    @Test
    void sendMessageDoesNothingWhenNoChatIdKnown() {
        TelegramChannel channel = channel("allowed_user");

        // No message has been consumed yet, so chatId is unknown
        channel.sendMessage("hello");

        verifyNoInteractions(telegramClient);
    }

    // -----------------------------------------------------------------------
    // Message formatting (Markdown → HTML)
    // -----------------------------------------------------------------------

    @Test
    void sendMessageConvertsMarkdownToTelegramHtml() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString()))
                .thenReturn("Here is **bold** text and a [link](https://example.com)");

        channel.consume(updateFrom("allowed_user", "hello", 42L, 567));

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                ParseMode.HTML.equals(msg.getParseMode()) &&
                        "Here is <strong>bold</strong> text and a <a href=\"https://example.com\">link</a>"
                                .equals(msg.getText())
        ));
    }


    @Test
    void sendMessageFallbacksToSendingRawTextWhenFailingToSendHtml() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString()))
                .thenReturn("Here is **bold** text and an image: ![An example image](/assets/images/clawrunr.png)");

        when(telegramClient.execute(argThat((SendMessage msg) ->
                ParseMode.HTML.equals(msg.getParseMode())
        ))).thenThrow(new TelegramApiException("Invalid HTML"));

        channel.consume(updateFrom("allowed_user", "hello", 42L, 567));

        verify(telegramClient).execute(argThat((SendMessage msg) ->
                msg.getParseMode() == null &&
                        msg.getText().equals("Here is **bold** text and an image: ![An example image](/assets/images/clawrunr.png)")
        ));
    }

    // -----------------------------------------------------------------------
    // Long message splitting
    // -----------------------------------------------------------------------

    @Test
    void splitsResponsesLongerThanTelegramLimitIntoMultipleMessages() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        String line = "word ".repeat(20);
        String paragraph = (line + "\n").repeat(45); // ~4500 chars, exceeds the 4096-char Telegram limit
        String longResponse = paragraph + "\n" + paragraph;
        when(agent.respondTo(anyString(), anyString())).thenReturn(longResponse);

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, atLeastOnce()).execute(captor.capture());

        List<SendMessage> sentMessages = captor.getAllValues();
        assertTrue(sentMessages.size() > 1, "expected the response to be split into multiple messages");
        for (SendMessage msg : sentMessages) {
            assertEquals("42", msg.getChatId());
            assertTrue(msg.getText().length() <= 4096, "chunk exceeds Telegram's message size limit");
        }

        String reconstructed = sentMessages.stream()
                .map(SendMessage::getText)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
        String normalizedOriginal = longResponse.replaceAll("\\s+", " ").trim();
        assertEquals(normalizedOriginal, reconstructed);
    }

    @Test
    void doesNotSplitResponsesWithinTelegramLimit() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        when(agent.respondTo(anyString(), anyString())).thenReturn("a short response");

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        verify(telegramClient, times(1)).execute(argThat((SendMessage msg) ->
                "42".equals(msg.getChatId())));
    }

    @Test
    void doesNotSplitEmojiSurrogatePairAcrossChunks() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        String longResponse = "a".repeat(4095) + "😀" + "a".repeat(500);
        when(agent.respondTo(anyString(), anyString())).thenReturn(longResponse);

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, atLeastOnce()).execute(captor.capture());

        List<SendMessage> sentMessages = captor.getAllValues();
        assertTrue(sentMessages.size() > 1, "expected the response to be split into multiple messages");
        for (SendMessage msg : sentMessages) {
            assertNoBrokenSurrogates(msg.getText());
        }

        String reconstructed = sentMessages.stream().map(SendMessage::getText).collect(Collectors.joining());
        assertEquals(longResponse, reconstructed);
    }

    @Test
    void splitChunksRespectRenderedHtmlLengthNotRawMarkdownLength() throws TelegramApiException {
        TelegramChannel channel = channel("allowed_user");
        String longResponse = "**bold** ".repeat(600);
        when(agent.respondTo(anyString(), anyString())).thenReturn(longResponse);

        channel.consume(updateFrom("allowed_user", "hello", 42L, null));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, atLeastOnce()).execute(captor.capture());

        List<SendMessage> sentMessages = captor.getAllValues();
        assertTrue(sentMessages.size() > 1, "expected the response to be split into multiple messages");
        for (SendMessage msg : sentMessages) {
            assertTrue(msg.getText().length() <= 4096, "rendered chunk exceeds Telegram's message size limit");
        }
    }

    private void assertNoBrokenSurrogates(String text) {
        if (text.isEmpty()) return;
        assertTrue(!Character.isHighSurrogate(text.charAt(text.length() - 1)),
                "chunk ends with an unpaired high surrogate: " + text);
        assertTrue(!Character.isLowSurrogate(text.charAt(0)),
                "chunk starts with an unpaired low surrogate: " + text);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private TelegramChannel channel(String allowedUsername) {
        return new TelegramChannel("token", allowedUsername, telegramClient, agent, new ChannelRegistry());
    }

    private Update updateFromUnknownUser(String username) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getFrom()).thenReturn(user);
        when(user.getUserName()).thenReturn(username);
        return update;
    }

    private Update updateFrom(String username, String text, long chatId, Integer messageThreadId) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getMessageThreadId()).thenReturn(messageThreadId);
        when(message.getFrom()).thenReturn(user);
        when(user.getUserName()).thenReturn(username);

        return update;
    }
}
