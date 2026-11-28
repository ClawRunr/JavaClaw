package ai.javaclaw.usage;

import java.time.Instant;

/**
 * The token usage of one model call: one row of the {@code token_usage} table.
 *
 * @param sessionId        the session the call belongs to; null for calls outside a session, such as a subagent's
 * @param conversation     the session's conversation key; null without a session
 * @param channel          the session's channel; null without a session
 * @param kind             the session's kind; null without a session
 * @param model            the model that answered, as the response reports it; null when it does not
 * @param inputTokens      input tokens, cached ones included
 * @param cacheReadTokens  input tokens read from the provider's prompt cache
 * @param cacheWriteTokens input tokens written to the provider's prompt cache
 */
public record UsageRecord(
        Instant recordedAt,
        String sessionId,
        String conversation,
        String channel,
        String kind,
        String model,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        int totalTokens
) {
}
