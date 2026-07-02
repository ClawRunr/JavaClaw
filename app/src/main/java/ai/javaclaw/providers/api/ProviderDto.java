package ai.javaclaw.providers.api;

/**
 * Read model for a configured provider as returned by {@code GET /api/providers}. Never carries the
 * raw API key — only a masked form for display.
 *
 * @param name         provider name (the key under {@code agent.llm.providers})
 * @param provider     provider type (openai | anthropic | ollama | google.genai | ...)
 * @param model        configured model id
 * @param baseUrl      base URL (relevant for ollama / self-hosted)
 * @param apiKeyMasked masked API key for cloud providers, e.g. {@code sk-...a1b2}; empty otherwise
 * @param isDefault    whether this is the reserved {@code default} provider
 */
public record ProviderDto(
        String name,
        String provider,
        String model,
        String baseUrl,
        String apiKeyMasked,
        boolean isDefault) {
}
