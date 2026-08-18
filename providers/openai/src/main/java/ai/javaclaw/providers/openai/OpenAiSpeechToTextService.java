package ai.javaclaw.providers.openai;

import ai.javaclaw.speech.SpeechToTextException;
import ai.javaclaw.speech.SpeechToTextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@ConditionalOnBean(OpenAiAudioTranscriptionModel.class)
public class OpenAiSpeechToTextService implements SpeechToTextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiSpeechToTextService.class);

    private final OpenAiAudioTranscriptionModel transcriptionModel;

    public OpenAiSpeechToTextService(OpenAiAudioTranscriptionModel transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    @Override
    public String transcribe(InputStream audioStream) {
        LOGGER.info("Transcribing audio via Spring AI OpenAI transcription");

        AudioTranscriptionResponse response = transcriptionModel.call(
                new AudioTranscriptionPrompt(new InputStreamResource(audioStream))
        );

        String text = response.getResult().getOutput();
        if (text == null || text.isBlank()) {
            throw new SpeechToTextException("OpenAI returned empty transcription");
        }

        LOGGER.info("OpenAI transcription completed successfully");
        return text.trim();
    }
}
