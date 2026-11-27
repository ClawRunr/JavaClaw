package ai.javaclaw.providers.claudecode;

/**
 * A failed Claude Code request. {@link #statusCode()} carries the HTTP status of the upstream
 * rejection, or the equivalent of the error Claude Code answered itself (429 for a plan limit,
 * 401 for a missing login, 503 when the CLI is not installed), or {@code null} when there is none.
 */
public class ClaudeCodeException extends RuntimeException {

    private final Integer statusCode;

    public ClaudeCodeException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
