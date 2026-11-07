package ai.javaclaw.approval;

/**
 * What a tool can do to the host or the outside world. Only {@link #READ_ONLY} tools run without
 * approval by default; a tool with no classification at all is treated as needing approval.
 */
public enum ToolRisk {

    /** Reads local state or the agent's own bookkeeping; changes nothing outside the process. */
    READ_ONLY,

    /** Creates, changes or deletes files, tasks or other persistent state. */
    WRITE,

    /** Runs code or commands, or hands work to something that can (subagents, MCP servers). */
    EXECUTE,

    /** Talks to hosts on the network on the agent's behalf. */
    NETWORK
}
