package ai.javaclaw;

import ai.javaclaw.tasks.TaskManager;
import ai.javaclaw.tools.JavaClawTaskTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class JavaClawConfiguration {

    public static final String AGENT_MD = "AGENT.private.md";

    private static final String NO_MODEL_MESSAGE =
            "No AI model has been configured. If you did configure a model recently, restart JavaClaw "
                    + "manually for the changes to take effect.";

    @Bean
    @ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = "unknown", matchIfMissing = true)
    public ChatModel chatModel() {
        return _ -> new ChatResponse(List.of(new Generation(new AssistantMessage(NO_MODEL_MESSAGE))));
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
    }

    @Bean
    public JavaClawTaskTool taskTool(TaskManager taskManager) {
        return JavaClawTaskTool.builder().taskManager(taskManager).build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel chatModel = chatModelProvider.getIfUnique(() ->
                prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(NO_MODEL_MESSAGE)))));
        return ChatClient.builder(chatModel);
    }

    /**
     * The main agent {@link ChatClient}, built from the {@code default} provider in
     * {@code agent.llm.providers}. Configuration changes trigger a full application restart, so this
     * is built once per context.
     */
    @Bean
    public ChatClient chatClient(MainChatClientProvider mainChatClientProvider) {
        return mainChatClientProvider.current();
    }
}
