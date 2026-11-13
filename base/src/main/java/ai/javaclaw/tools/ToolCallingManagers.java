package ai.javaclaw.tools;

import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * The {@link ToolCallingManager} the main agent's tool-calling advisors run tools with.
 */
public final class ToolCallingManagers {

    private ToolCallingManagers() {
    }

    /**
     * A manager without per-turn tool call limits. Spring AI 2.1 caps a turn at 40 calls per tool and
     * 150 in total and then ends the turn; JavaClaw agents (long-running tasks in particular) had no
     * such cap before, so it stays off.
     */
    public static ToolCallingManager unlimited() {
        return ToolCallingManager.builder()
                .unlimitedCallsPerTool()
                .unlimitedTotalToolCalls()
                .build();
    }
}
