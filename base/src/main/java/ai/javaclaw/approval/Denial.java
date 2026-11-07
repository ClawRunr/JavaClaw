package ai.javaclaw.approval;

/**
 * Why a tool call was not run. The same text reaches the model (as the tool's result) and the
 * owner (on the approval card or in a task result), so both see the real reason.
 *
 * @param reason the kind of refusal
 * @param detail a human-readable explanation, e.g. "the web chat client disconnected"
 */
public record Denial(Reason reason, String detail) {

    public enum Reason {
        /** An {@code agent.approval.rules.deny} rule matched. */
        RULE("rule"),
        /** The owner pressed deny. */
        USER("user"),
        /** Nobody answered within {@code agent.approval.timeout}. */
        TIMEOUT("timeout"),
        /** The call needed approval, but there was no one who could be asked. */
        NOBODY_TO_ASK("nobody-to-ask");

        private final String wireValue;

        Reason(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /** The tool result the model receives instead of the tool's output. */
    public String modelMessage(String toolName) {
        return "Tool '%s' was NOT run: %s. Do not call '%s' again for this request. If another available tool "
                .formatted(toolName, detail, toolName)
                + "can accomplish the goal, use it; otherwise tell the user what could not be done and why.";
    }
}
