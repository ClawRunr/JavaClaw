package ai.javaclaw.speech;

import java.io.InputStream;

public interface SpeechToTextService {

    String transcribe(InputStream audioStream);
}
