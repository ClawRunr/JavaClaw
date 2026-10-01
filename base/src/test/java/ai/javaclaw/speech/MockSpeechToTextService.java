package ai.javaclaw.speech;

import java.io.InputStream;

public class MockSpeechToTextService implements SpeechToTextService {

    @Override
    public String transcribe(InputStream audioStream) {
        return "[voice message]";
    }
}
