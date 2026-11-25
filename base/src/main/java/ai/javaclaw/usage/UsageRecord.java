package ai.javaclaw.usage;

import java.time.Instant;

/**
 * One recorded model call's token usage.
 */
public record UsageRecord(
        Instant recordedAt,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String model,
        String provider,
        UsageSource source,
        String conversationId,
        String taskId
) {
}
