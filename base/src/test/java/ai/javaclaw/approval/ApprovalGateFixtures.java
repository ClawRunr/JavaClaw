package ai.javaclaw.approval;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Builds gates and tools for approval tests. */
public final class ApprovalGateFixtures {

    /** Where tests pretend the runtime configuration lives. */
    public static final Path CONFIG_FILE = Path.of("app", "src", "main", "resources", "application.private.yaml");

    /** Test tools, one per risk level, plus one nobody classified. */
    public static final ToolRiskContributor TEST_TOOL_RISKS = () -> Map.of(
            "readNotes", ToolRisk.READ_ONLY,
            "writeNote", ToolRisk.WRITE,
            "runScript", ToolRisk.EXECUTE,
            "fetchUrl", ToolRisk.NETWORK);

    private ApprovalGateFixtures() {
    }

    public static ApprovalGate defaultGate() {
        return gate(ApprovalProperties.defaults(), new PendingApprovals());
    }

    public static ApprovalGate gate(ApprovalProperties properties, PendingApprovals pendingApprovals) {
        return new ApprovalGate(properties,
                new ToolRiskRegistry(List.of(new DefaultToolRisks(), TEST_TOOL_RISKS)),
                new ProtectedActions(CONFIG_FILE), pendingApprovals, Clock.systemUTC());
    }

    public static ApprovalProperties rules(List<String> allow, List<String> ask, List<String> deny) {
        return new ApprovalProperties(null, null, new ApprovalProperties.Rules(allow, ask, deny));
    }

    /** A tool that records every input, and the tool context, it runs with. */
    public static final class RecordingTool implements ToolCallback {

        private final String name;
        private final List<String> runs = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> contexts = new CopyOnWriteArrayList<>();

        public RecordingTool(String name) {
            this.name = name;
        }

        public List<String> runs() {
            return runs;
        }

        public List<Map<String, Object>> contexts() {
            return contexts;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("Test tool " + name)
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            runs.add(toolInput);
            contexts.add(toolContext == null ? Map.of() : toolContext.getContext());
            return "ran " + name;
        }
    }
}
