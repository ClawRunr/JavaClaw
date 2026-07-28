package ai.javaclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgent.class);

    private final ChatClient chatClient;

    public DefaultAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String respondTo(String conversationId, String question) {
        return chatClient
                .prompt(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    @Override
    public String respondTo(String conversationId, String question, ResponseListener listener) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            chatClient
                    .prompt(question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        listener.onToken(token);
                    })
                    .blockLast();
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call
            String response = respondTo(conversationId, question);
            listener.onToken(response);
            listener.onComplete();
            return response;
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", conversationId, e);
            listener.onError(summarizeError(e));
            return fullResponse.toString();
        }
        listener.onComplete();
        return fullResponse.toString();
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result) {
        return chatClient
                .prompt(input)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(result);
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
