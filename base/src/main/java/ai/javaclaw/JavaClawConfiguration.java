package ai.javaclaw;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.tasks.TaskManager;
import ai.javaclaw.tools.AgentEnvironment;
import ai.javaclaw.tools.AutoDiscoveredTool;
import ai.javaclaw.tools.CheckListTool;
import ai.javaclaw.tools.McpTool;
import ai.javaclaw.tools.TaskTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Configuration
public class JavaClawConfiguration {

    public static final String AGENT_MD = "AGENT.private.md";

    @Bean
    @ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = "unknown", matchIfMissing = true)
    public ChatModel chatModel() {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("No AI model has been configured. If you did configure a model recently, restart JavaClaw manually for the changes to take effect."))));
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
    }

    @Bean
    public TaskTool taskTool(TaskManager taskManager) {
        return TaskTool.builder().taskManager(taskManager).build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel chatModel = chatModelProvider.getIfUnique(() -> prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("No AI model has been configured. If you did configure a model recently, restart JavaClaw manually for the changes to take effect.")))));
        return ChatClient.builder(chatModel);
    }

    @Bean
    @DependsOn({"mcpHeaderCustomizer"})
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 ChatMemory chatMemory,
                                 ObjectProvider<ToolSearchToolCallAdvisor> toolSearchToolCallAdvisorProvider,
                                 SyncMcpToolCallbackProvider mcpToolProvider,
                                 TaskManager taskManager,
                                 ConfigurationManager configurationManager,
                                 @Value("${agent.workspace:Unknown}") Resource workspace,
                                 Set<AutoDiscoveredTool<?>> autoDiscoveredTools
    ) throws IOException {

        Resource agentMd = workspace.createRelative(AGENT_MD);
        if (!agentMd.exists()) {
            agentMd = workspace.createRelative("AGENT.md");
        }
        String agentPrompt = agentMd.getContentAsString(StandardCharsets.UTF_8) + System.lineSeparator()
                + workspace.createRelative("INFO.md").getContentAsString(StandardCharsets.UTF_8) + System.lineSeparator();

        ToolCallAdvisor toolCallAdvisor = toolSearchToolCallAdvisorProvider.getIfAvailable();
        if (toolCallAdvisor == null) {
            toolCallAdvisor = ToolCallAdvisor.builder().build();
        }

        chatClientBuilder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultSystem(p -> p.text(agentPrompt).param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info()))
                .defaultToolCallbacks(mcpToolProvider.getToolCallbacks())
                .defaultToolCallbacks(SkillsTool.builder().addSkillsDirectory(skillsDir(workspace).toString()).build())
                .defaultTools(
                        TaskTool.builder().taskManager(taskManager).build(),
                        CheckListTool.builder().build(),
                        McpTool.builder().configurationManager(configurationManager).build(),
                        //Bash execution tool
                        //ShellTools.builder().build(),// built-in shell tools
                        // Read, Write and Edit files tool
                        FileSystemTools.builder().build(),// built-in file system tools
                        // Smart web fetch tool
                        SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build())
                .defaultAdvisors(
                        toolCallAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                );

        autoDiscoveredTools.forEach(autoDiscoveredTool -> chatClientBuilder.defaultTools(autoDiscoveredTool.tool()));
        return chatClientBuilder.build();
    }

    private static Path skillsDir(Resource workspace) throws IOException {
        Path skillsDir = workspace.getFilePath().resolve("skills");
        Files.createDirectories(skillsDir);
        return skillsDir;
    }
}
