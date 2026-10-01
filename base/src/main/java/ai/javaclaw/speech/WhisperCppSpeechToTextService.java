package ai.javaclaw.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "speech.provider", havingValue = "whisper-cpp")
public class WhisperCppSpeechToTextService implements SpeechToTextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhisperCppSpeechToTextService.class);

    private final String modelPath;

    public WhisperCppSpeechToTextService(
            @Value("${speech.whisper-cpp.model-path}") String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public String transcribe(InputStream audioStream) {
        LOGGER.info("Transcribing audio via whisper-cpp (model: {})", modelPath);

        Path oggFile = null;
        Path wavFile = null;
        Path outputFile = null;

        try {
            oggFile = Files.createTempFile("whisper-input-", ".ogg");
            Files.write(oggFile, audioStream.readAllBytes());

            wavFile = Files.createTempFile("whisper-input-", ".wav");
            convertOggToWav(oggFile, wavFile);

            outputFile = Files.createTempFile("whisper-output-", ".txt");
            Files.deleteIfExists(outputFile);

            ProcessBuilder pb = new ProcessBuilder(
                    "whisper-cli",
                    "-m", modelPath,
                    "-f", wavFile.toString(),
                    "-otxt",
                    "-of", outputFile.toString().replace(".txt", ""),
                    "--no-prints"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new SpeechToTextException("whisper-cli timed out after 60 seconds");
            }

            if (process.exitValue() != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                throw new SpeechToTextException("whisper-cli exited with code " + process.exitValue() + ": " + error);
            }

            if (!Files.exists(outputFile)) {
                throw new SpeechToTextException("whisper-cli did not produce output file");
            }

            String text = Files.readString(outputFile).trim();
            if (text.isBlank()) {
                throw new SpeechToTextException("whisper-cli returned empty transcription");
            }

            LOGGER.info("whisper-cpp transcription completed successfully");
            return text;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SpeechToTextException("Failed to run whisper-cli", e);
        } finally {
            deleteSilently(oggFile);
            deleteSilently(wavFile);
            deleteSilently(outputFile);
        }
    }

    private void convertOggToWav(Path oggFile, Path wavFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", oggFile.toString(), "-ar", "16000", "-ac", "1", wavFile.toString()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new SpeechToTextException("ffmpeg conversion timed out");
        }

        if (process.exitValue() != 0) {
            String error = new String(process.getInputStream().readAllBytes());
            throw new SpeechToTextException("ffmpeg conversion failed: " + error);
        }
    }

    private void deleteSilently(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}
