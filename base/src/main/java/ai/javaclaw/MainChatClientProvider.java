package ai.javaclaw;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.configuration.ConfigurationRefreshedEvent;
import ai.javaclaw.llm.ChatClientRegistry;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.SubagentStore;
import ai.javaclaw.tasks.TaskManager;
import ai.javaclaw.tools.AgentEnvironment;
import ai.javaclaw.tools.AutoDiscoveredTool;
import ai.javaclaw.tools.CheckListTool;
import ai.javaclaw.tools.McpTool;
import ai.javaclaw.tools.JavaClawTaskTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and holds the main agent {@link ChatClient} (default provider + all tools, advisors and
 * subagent routing). Rebuilt when the provider configuration is hot-reloaded so changes to the
 * {@code default} provider and the set of subagent providers take effect without restarting.
 */
@Component
@DependsOn({"mcpHeaderCustomizer"})
public class MainChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(MainChatClientProvider.class);
    private static final String DEFAULT = LlmProviderProperties.DEFAULT_PROVIDER_NAME;

    private final ChatClientRegistry registry;
    private final ChatMemory chatMemory;
    private final ObjectProvider<ToolSearchToolCallingAdvisor> toolSearchAdvisorProvider;
    private final SyncMcpToolCallbackProvider mcpToolProvider;
    private final TaskManager taskManager;
    private final ConfigurationManager configurationManager;
    private final SubagentStore subagentStore;
    private final Set<AutoDiscoveredTool<?>> autoDiscoveredTools;
    private final Resource workspace;
    private final List<Resource> skillPaths;

    private volatile ChatClient delegate;

    public MainChatClientProvider(ChatClientRegistry registry,
                                  ChatMemory chatMemory,
                                  ObjectProvider<ToolSearchToolCallingAdvisor> toolSearchAdvisorProvider,
                                  SyncMcpToolCallbackProvider mcpToolProvider,
                                  TaskManager taskManager,
                                  ConfigurationManager configurationManager,
                                  SubagentStore subagentStore,
                                  Set<AutoDiscoveredTool<?>> autoDiscoveredTools,
                                  @Value("${agent.workspace:Unknown}") Resource workspace,
                                  @Value("${agent.skills.paths}") List<Resource> skillPaths) {
        this.registry = registry;
        this.chatMemory = chatMemory;
        this.toolSearchAdvisorProvider = toolSearchAdvisorProvider;
        this.mcpToolProvider = mcpToolProvider;
        this.taskManager = taskManager;
        this.configurationManager = configurationManager;
        this.subagentStore = subagentStore;
        this.autoDiscoveredTools = autoDiscoveredTools;
        this.workspace = workspace;
        this.skillPaths = skillPaths;
        this.delegate = build();
    }

    /** The current main {@link ChatClient}. Accessed via {@link ai.javaclaw.llm.DelegatingChatClient}. */
    public ChatClient current() {
        return delegate;
    }

    @EventListener
    public void onConfigurationRefreshed(ConfigurationRefreshedEvent event) {
        if (event.changedKeys().isEmpty() || event.hasChangeUnder("agent.llm.providers")) {
            log.info("Rebuilding main chat client after configuration refresh");
            this.delegate = build();
        }
    }

    private ChatClient build() {
        try {
            ChatClient.Builder builder = registry.builderFor(DEFAULT);

            String agentPrompt = readSystemPrompt();
            ToolCallingAdvisor toolCallAdvisor = toolSearchAdvisorProvider.getIfAvailable();
            if (toolCallAdvisor == null) {
                toolCallAdvisor = ToolCallingAdvisor.builder().build();
            }

            builder.defaultAdvisors(new SimpleLoggerAdvisor())
                    //.defaultSystem(p -> p.text(agentPrompt).param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info()))
                    .defaultTools((Object[]) mcpToolProvider.getToolCallbacks())
                    .defaultTools(SkillsTool.builder()
                            .addSkillsDirectory(skillsDir().toString())
                            .addSkillsResources(skillPaths)
                            .build())
                    .defaultTools(
                            JavaClawTaskTool.builder().taskManager(taskManager).build(),
                            CheckListTool.builder().build(),
                            McpTool.builder().configurationManager(configurationManager).build(),
                            FileSystemTools.builder().build(),
                            SmartWebFetchTool.builder(registry.getOrDefault(DEFAULT)).build())
                    .defaultAdvisors(toolCallAdvisor, MessageChatMemoryAdvisor.builder(chatMemory).build());

            ToolCallback subagentTaskTool = buildSubagentTaskTool();
            if (subagentTaskTool != null) {
                builder.defaultTools(subagentTaskTool);
            }

            autoDiscoveredTools.forEach(tool -> builder.defaultTools(tool.tool()));
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build the main chat client", e);
        }
    }

    /**
     * Builds the agent-utils subagent dispatch tool: a Claude subagent type backed by a per-provider
     * {@link ChatClient.Builder} map, with subagent definitions discovered from
     * {@code workspace/agents/}.
     */
    private ToolCallback buildSubagentTaskTool() {
        Path agentsDir = subagentStore.agentsDirectory();
        if (agentsDir == null) {
            return null;
        }
        try {
            Files.createDirectories(agentsDir);

            // Agents reference their own provider entry by name, so builders are keyed by provider name.
            Map<String, ChatClient.Builder> builders = new LinkedHashMap<>();
            for (String name : registry.availableNames()) {
                builders.put(name, registry.builderFor(name));
            }
            if (builders.isEmpty()) {
                return null;
            }

            SubagentType claudeType = ClaudeSubagentType.builder()
                    .chatClientBuilders(builders)
                    .skillsDirectories(skillsDir().toString())
                    .build();
            return TaskTool.builder()
                    .subagentReferences(ClaudeSubagentReferences.fromRootDirectory(agentsDir.toString()))
                    .subagentTypes(claudeType)
                    .build();
        } catch (IOException e) {
            log.warn("Failed to build subagent task tool: {}", e.getMessage());
            return null;
        }
    }

    private String readSystemPrompt() throws IOException {
        Resource agentMd = workspace.createRelative(JavaClawConfiguration.AGENT_MD);
        if (!agentMd.exists()) {
            agentMd = workspace.createRelative("AGENT.md");
        }
        return agentMd.getContentAsString(StandardCharsets.UTF_8) + System.lineSeparator()
                + workspace.createRelative("INFO.md").getContentAsString(StandardCharsets.UTF_8) + System.lineSeparator();
    }

    private Path skillsDir() throws IOException {
        Path skillsDir = workspace.getFilePath().resolve("skills");
        Files.createDirectories(skillsDir);
        return skillsDir;
    }
}
