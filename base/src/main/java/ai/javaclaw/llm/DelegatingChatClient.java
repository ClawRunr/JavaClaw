package ai.javaclaw.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.function.Supplier;

/**
 * A {@link ChatClient} that forwards every call to a delegate resolved on each invocation. This lets
 * the main agent keep a single injected {@code ChatClient} reference while the underlying client is
 * rebuilt (e.g. when the default provider's configuration changes) without restarting the context.
 */
public class DelegatingChatClient implements ChatClient {

    private final Supplier<ChatClient> delegate;

    public DelegatingChatClient(Supplier<ChatClient> delegate) {
        this.delegate = delegate;
    }

    private ChatClient delegate() {
        return delegate.get();
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return delegate().prompt();
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return delegate().prompt(content);
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        return delegate().prompt(prompt);
    }

    @Override
    public Builder mutate() {
        return delegate().mutate();
    }
}
