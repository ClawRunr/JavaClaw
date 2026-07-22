package ai.javaclaw.channels.telegram;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.ArrayList;
import java.util.List;

import static java.util.Optional.ofNullable;

public class TelegramChannel implements Channel, SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramChannel.class);

    private static final int MAX_MESSAGE_LENGTH = 4096;

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder()
            .escapeHtml(true)
            .extensions(List.of(StrikethroughExtension.create()))
            .build();

    private final String botToken;
    private final String allowedUsername;
    private final TelegramClient telegramClient;
    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private Long chatId;

    public TelegramChannel(String botToken, String allowedUsername, Agent agent, ChannelRegistry channelRegistry) {
        this(botToken, allowedUsername, new OkHttpTelegramClient(botToken), agent, channelRegistry);
    }

    TelegramChannel(String botToken, String allowedUsername, TelegramClient telegramClient, Agent agent, ChannelRegistry channelRegistry) {
        this.botToken = botToken;
        this.allowedUsername = normalizeUsername(allowedUsername);
        this.telegramClient = telegramClient;
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        channelRegistry.registerChannel(this);
        LOGGER.info("Started Telegram integration");
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (!(update.hasMessage() && update.getMessage().hasText())) return;

        Message requestMessage = update.getMessage();
        String userName = requestMessage.getFrom() == null ? null : requestMessage.getFrom().getUserName();
        if (!isAllowedUser(userName)) {
            LOGGER.warn("Ignoring Telegram message from unauthorized username '{}'", userName);
            sendMessage("I'm sorry, I don't accept instructions from you.");
            return;
        }

        String messageText = requestMessage.getText();
        this.chatId = requestMessage.getChatId();
        Integer messageThreadId = requestMessage.getMessageThreadId();
        channelRegistry.publishMessageReceivedEvent(new TelegramChannelMessageReceivedEvent(getName(), messageText, chatId, messageThreadId));
        String response = agent.respondTo(getConversationId(chatId, messageThreadId), messageText);
        sendMessage(chatId, messageThreadId, response);
    }

    @Override
    public void sendMessage(String message) {
        if (chatId == null) {
            LOGGER.error("No known chatId, cannot send message '{}'", message);
            return;
        }
        sendMessage(chatId, null, message);
    }

    public void sendMessage(long chatId, Integer messageThreadId, String message) {
        for (String chunk : splitMessage(message, MAX_MESSAGE_LENGTH)) {
            sendSingleMessage(chatId, messageThreadId, chunk);
        }
    }

    private void sendSingleMessage(long chatId, Integer messageThreadId, String message) {
        String formattedHtmlMessage = convertMarkdownToTelegramHtml(message);

        SendMessage htmlMessage = SendMessage.builder()
                .chatId(chatId)
                .messageThreadId(messageThreadId)
                .text(formattedHtmlMessage)
                .parseMode(ParseMode.HTML)
                .build();

        try {
            telegramClient.execute(htmlMessage);
        } catch (TelegramApiException e) {
            LOGGER.warn("Failed to send HTML parsed message, falling back to raw text.", e);

            SendMessage fallbackMessage = SendMessage.builder()
                    .chatId(chatId)
                    .messageThreadId(messageThreadId)
                    .text(message)
                    .build();

            try {
                telegramClient.execute(fallbackMessage);
            } catch (TelegramApiException fallbackEx) {
                throw new RuntimeException("Failed to send both HTML and fallback messages", fallbackEx);
            }
        }
    }

    private List<String> splitMessage(String message, int maxLength) {
        if (message == null) return List.of("");
        if (renderedLength(message) <= maxLength) return List.of(message);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : message.split("\n", -1)) {
            while (renderedLength(line) > maxLength) {
                flush(chunks, current);
                var splitAt = findSafeSplitIndex(line, maxLength);
                chunks.add(line.substring(0, splitAt));
                line = line.substring(splitAt);
            }

            String candidate = current.isEmpty() ? line : current + "\n" + line;
            if (!current.isEmpty() && renderedLength(candidate) > maxLength) {
                flush(chunks, current);
                candidate = line;
            }
            current.setLength(0);
            current.append(candidate);
        }
        flush(chunks, current);

        return chunks;
    }

    /**
     * Finds the largest prefix of {@code line} whose rendered HTML form fits within
     * {@code maxLength}, backing off by one code unit if the cut would split a surrogate pair.
     */
    private int findSafeSplitIndex(String line, int maxLength) {
        var lowestCandidate = 0;
        var highestCandidate = Math.min(maxLength, line.length());
        while (lowestCandidate < highestCandidate) {
            var candidateIndex = (lowestCandidate + highestCandidate + 1) / 2;
            if (renderedLength(line.substring(0, candidateIndex)) <= maxLength) {
                lowestCandidate = candidateIndex;
            } else {
                highestCandidate = candidateIndex - 1;
            }
        }
        // Force at least one character even if none fits, so the caller always makes progress.
        var splitIndex = Math.max(lowestCandidate, 1);
        if (splitIndex > 1 && Character.isHighSurrogate(line.charAt(splitIndex - 1))) {
            splitIndex--;
        }
        return splitIndex;
    }

    private int renderedLength(String text) {
        return convertMarkdownToTelegramHtml(text).length();
    }

    private void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private String convertMarkdownToTelegramHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";

        Node document = MARKDOWN_PARSER.parse(markdown);
        String html = HTML_RENDERER.render(document);

        // Minimalist replacement logic to handle unsupported structural tags
        return html.replace("<p>", "").replace("</p>", "\n")
                .replaceAll("(?s)<h[1-6]>(.*?)</h[1-6]>", "<b>$1</b>\n")
                .replaceAll("(?s)<li>(.*?)</li>", "- $1\n")
                .replace("<ul>", "").replace("</ul>", "")
                .replace("<ol>", "").replace("</ol>", "")
                .replace("<hr />", "\n")
                .trim();
    }

    private boolean isAllowedUser(String userName) {
        String normalizedUserName = normalizeUsername(userName);
        return normalizedUserName != null && normalizedUserName.equalsIgnoreCase(allowedUsername);
    }

    private static String normalizeUsername(String userName) {
        if (userName == null) {
            return null;
        }

        String normalizedUserName = userName.trim();
        if (normalizedUserName.startsWith("@")) {
            normalizedUserName = normalizedUserName.substring(1);
        }

        return normalizedUserName.isBlank() ? null : normalizedUserName;
    }

    private String getConversationId(Long chatId, Integer messageThreadId) {
        return "telegram-" + chatId + ofNullable(messageThreadId).map(i -> "-" + i).orElse("");
    }

    static class TelegramChannelMessageReceivedEvent extends ChannelMessageReceivedEvent {

        private final long chatId;
        private final Integer messageThreadId;

        public TelegramChannelMessageReceivedEvent(String channel, String message, long chatId, Integer messageThreadId) {
            super(channel, message);
            this.chatId = chatId;
            this.messageThreadId = messageThreadId;
        }

        public long getChatId() {
            return chatId;
        }

        public Integer getMessageThreadId() {
            return messageThreadId;
        }
    }
}