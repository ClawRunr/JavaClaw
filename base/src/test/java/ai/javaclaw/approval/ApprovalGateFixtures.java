package ai.javaclaw.approval;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Builds gates and tools for approval tests. */
public final class ApprovalGateFixtures {

    /** {@code readNotes} and tool search run without asking; everything else asks. */
    public static final ApprovalProperties DEFAULTS = new ApprovalProperties(null, List.of("readNotes", "toolSearchTool"));

    private ApprovalGateFixtures() {
    }

    public static ApprovalGate defaultGate() {
        return gate(DEFAULTS, new PendingApprovals());
    }

    public static ApprovalGate gate(ApprovalProperties properties, PendingApprovals pendingApprovals) {
        return new ApprovalGate(properties, pendingApprovals, Clock.systemUTC());
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
