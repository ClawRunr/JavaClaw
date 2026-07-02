package ai.javaclaw.providers.api;

import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;

/**
 * Write model for creating/updating a provider and for the standalone connection test. Carries the
 * raw API key (request bodies only — never serialized back to the client).
 */
public record ProviderForm(
        String name,
        String provider,
        String model,
        String apiKey,
        String baseUrl) {

    public ProviderConfig toConfig() {
        return new ProviderConfig(provider, apiKey, baseUrl, model);
    }
}
