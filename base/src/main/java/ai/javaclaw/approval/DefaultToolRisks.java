package ai.javaclaw.approval;

import java.util.Map;

import static ai.javaclaw.approval.ToolRisk.EXECUTE;
import static ai.javaclaw.approval.ToolRisk.NETWORK;
import static ai.javaclaw.approval.ToolRisk.READ_ONLY;
import static ai.javaclaw.approval.ToolRisk.WRITE;
import static java.util.Map.entry;

/**
 * Risk of the tools built into {@code base}: JavaClaw's own tools, the spring-ai-agent-utils tools
 * the main agent or its subagents use, and Spring AI's tool search. Plugin tools are classified by
 * their plugin; MCP tools are not classified, so they always need approval unless a rule says
 * otherwise.
 */
public class DefaultToolRisks implements ToolRiskContributor {

    private static final Map<String, ToolRisk> RISKS = Map.ofEntries(
            // Spring AI tool search: looks tools up in the local index
            entry("toolSearchTool", READ_ONLY),

            // JavaClaw tools
            entry("CheckListTool", READ_ONLY),
            entry("listRecurringTasks", READ_ONLY),
            entry("createTask", WRITE),
            entry("scheduleTask", WRITE),
            entry("scheduleRecurringTask", WRITE),
            entry("deleteRecurringTask", WRITE),
            entry("addStreamableHttpMcpServer", EXECUTE),
            entry("addStdioMcpServer", EXECUTE),

            // spring-ai-agent-utils tools given to the main agent
            entry("Skill", READ_ONLY),
            entry("Read", READ_ONLY),
            entry("Write", WRITE),
            entry("Edit", WRITE),
            entry("WebFetch", NETWORK),
            entry("WebSearch", NETWORK),
            // Dispatches a subagent that has shell, file and web tools of its own (see ClaudeSubagentType)
            entry("Task", EXECUTE),

            // spring-ai-agent-utils tools JavaClaw does not give the main agent today; classified so
            // a future wiring change cannot slip them in unclassified-but-allowed by a rule typo
            entry("Glob", READ_ONLY),
            entry("Grep", READ_ONLY),
            entry("ListDirectory", READ_ONLY),
            entry("TodoWrite", READ_ONLY),
            entry("BashOutput", READ_ONLY),
            entry("MemoryView", READ_ONLY),
            entry("Bash", EXECUTE),
            entry("KillShell", EXECUTE),
            entry("MemoryCreate", WRITE),
            entry("MemoryStrReplace", WRITE),
            entry("MemoryInsert", WRITE),
            entry("MemoryDelete", WRITE),
            entry("MemoryRename", WRITE));

    @Override
    public Map<String, ToolRisk> toolRisks() {
        return RISKS;
    }
}
