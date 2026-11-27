package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.utils.NamedThreadFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

/** Receives the messages that {@code wacli sync} posts to us and hands them to {@link WhatsApp}. */
@RestController
@ConditionalOnProperty(prefix = "agent.channels.whatsapp", name = "enabled", havingValue = "true")
public class WhatsAppWebhookController {

    static final String PATH = "/api/whatsapp/webhook";

    private final WhatsApp whatsApp;
    private final ExecutorService worker;

    public WhatsAppWebhookController(WhatsApp whatsApp) {
        this.whatsApp = whatsApp;
        this.worker = newSingleThreadExecutor(new NamedThreadFactory("javaclaw-whatsapp", true));
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
    }

    /** Answers wacli straight away and handles the message in the background, as a reply takes seconds. */
    @PostMapping(PATH)
    public ResponseEntity<Void> webhook(@RequestBody(required = false) WacliWebhookPayload payload) {
        worker.execute(() -> whatsApp.onIncomingMessage(payload));
        return ResponseEntity.ok().build();
    }
}
