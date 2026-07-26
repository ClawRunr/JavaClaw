package ai.javaclaw.agent;

import java.util.function.Consumer;

/**
 * Callback for observing an agent response as it is produced. Callers that can
 * render progress incrementally (e.g. the web chat) supply an implementation;
 * where the tokens go (WebSocket, SSE, console) is entirely the caller's concern.
 */
public interface ResponseListener {

    /**
     * Called for every token as it arrives from the model.
     */
    void onToken(String token);

    /**
     * Called once after the last token; no further callbacks follow.
     */
    void onComplete();

    /**
     * Called when the response fails; no further callbacks follow. Tokens already
     * delivered via {@link #onToken(String)} may precede the failure.
     */
    void onError(String message);

    /**
     * Creates a listener from three lambdas, avoiding an anonymous class at the call site.
     */
    static ResponseListener of(Consumer<String> onToken, Runnable onComplete, Consumer<String> onError) {
        return new ResponseListener() {

            @Override
            public void onToken(String token) {
                onToken.accept(token);
            }

            @Override
            public void onComplete() {
                onComplete.run();
            }

            @Override
            public void onError(String message) {
                onError.accept(message);
            }
        };
    }
}
