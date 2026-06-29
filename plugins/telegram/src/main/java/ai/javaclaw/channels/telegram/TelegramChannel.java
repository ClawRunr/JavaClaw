package ai.javaclaw.channels.telegram;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.speech.SpeechToTextService;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public class TelegramChannel implements Channel, SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramChannel.class);

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
    private final SpeechToTextService speechToTextService;
    private final TelegramVoiceDownloader voiceDownloader;
    private Long chatId;

    public TelegramChannel(String botToken, String allowedUsername, Agent agent, ChannelRegistry channelRegistry, SpeechToTextService speechToTextService) {
        this(botToken, allowedUsername, new OkHttpTelegramClient(botToken), agent, channelRegistry, speechToTextService);
    }

    TelegramChannel(String botToken, String allowedUsername, TelegramClient telegramClient, Agent agent, ChannelRegistry channelRegistry, SpeechToTextService speechToTextService) {
        this(botToken, allowedUsername, telegramClient, agent, channelRegistry, speechToTextService, new TelegramVoiceDownloader(telegramClient, botToken));
    }

    TelegramChannel(String botToken, String allowedUsername, TelegramClient telegramClient, Agent agent, ChannelRegistry channelRegistry, SpeechToTextService speechToTextService, TelegramVoiceDownloader voiceDownloader) {
        this.botToken = botToken;
        this.allowedUsername = normalizeUsername(allowedUsername);
        this.telegramClient = telegramClient;
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        this.speechToTextService = speechToTextService;
        this.voiceDownloader = voiceDownloader;
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
        if (!update.hasMessage()) return;

        Message requestMessage = update.getMessage();
        String userName = requestMessage.getFrom() == null ? null : requestMessage.getFrom().getUserName();
        if (!isAllowedUser(userName)) {
            LOGGER.warn("Ignoring Telegram message from unauthorized username '{}'", userName);
            sendMessage("I'm sorry, I don't accept instructions from you.");
            return;
        }

        Optional<String> messageText = resolveMessageText(requestMessage);
        if (messageText.isEmpty()) return;

        this.chatId = requestMessage.getChatId();
        Integer messageThreadId = requestMessage.getMessageThreadId();
        channelRegistry.publishMessageReceivedEvent(new TelegramChannelMessageReceivedEvent(getName(), messageText.get(), chatId, messageThreadId));
        String response = agent.respondTo(getConversationId(chatId, messageThreadId), messageText.get());
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

    private Optional<String> resolveMessageText(Message message) {
        if (message.hasText()) {
            return Optional.of(message.getText());
        }
        if (message.hasVoice()) {
            return transcribeVoice(message);
        }
        return Optional.empty();
    }

    private Optional<String> transcribeVoice(Message message) {
        LOGGER.info("Voice message received, downloading audio");
        try (InputStream voiceStream = voiceDownloader.download(message)) {
            String transcribed = speechToTextService.transcribe(voiceStream);
            LOGGER.info("Voice message transcribed successfully");
            return Optional.of(transcribed);
        } catch (IOException | TelegramApiException e) {
            LOGGER.error("Failed to process voice message", e);
            return Optional.empty();
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
