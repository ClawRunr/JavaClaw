package ai.javaclaw.approval;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Policies and tools for approval tests. */
public final class ApprovalFixtures {

    /** {@code readNotes} and tool search run without asking; everything else asks. */
    public static final ApprovalProperties DEFAULTS = new ApprovalProperties(List.of("readNotes", "toolSearchTool"));

    private ApprovalFixtures() {
    }

    public static ApprovalPolicy defaultPolicy() {
        return new ApprovalPolicy(DEFAULTS);
    }

    /** A tool that records every input, and the tool context, it runs with. */
    public static final class RecordingTool implements ToolCallback {

        private final String name;
        private final List<String> runs = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> contexts = new CopyOnWriteArrayList<>();
        private final List<String> log;

        public RecordingTool(String name) {
            this(name, new CopyOnWriteArrayList<>());
        }

        /** @param log gets {@code name:input} for every run, so tests can share one across tools */
        public RecordingTool(String name, List<String> log) {
            this.name = name;
            this.log = log;
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
            log.add(name + ":" + toolInput);
            contexts.add(toolContext == null ? Map.of() : toolContext.getContext());
            return "ran " + name;
        }
    }
}
