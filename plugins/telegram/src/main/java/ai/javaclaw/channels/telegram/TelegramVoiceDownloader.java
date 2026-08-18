package ai.javaclaw.channels.telegram;

import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

class TelegramVoiceDownloader {

    private final TelegramClient telegramClient;
    private final String botToken;

    TelegramVoiceDownloader(TelegramClient telegramClient, String botToken) {
        this.telegramClient = telegramClient;
        this.botToken = botToken;
    }

    InputStream download(Message message) throws TelegramApiException, IOException {
        String fileId = message.getVoice().getFileId();
        GetFile getFile = new GetFile(fileId);
        String filePath = telegramClient.execute(getFile).getFilePath();
        String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
        return URI.create(fileUrl).toURL().openStream();
    }
}
