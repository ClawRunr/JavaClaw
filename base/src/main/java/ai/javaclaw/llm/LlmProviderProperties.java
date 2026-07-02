package ai.javaclaw.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named map of LLM providers, bound from {@code agent.llm.providers.*}.
 * <p>
 * Example configuration:
 * <pre>
 * agent:
 *   llm:
 *     providers:
 *       default:
 *         provider: openai
 *         api-key: sk-...
 *         model: gpt-4o
 *       local:
 *         provider: ollama
 *         base-url: http://localhost:11434
 *         model: llama3.2
 * </pre>
 */
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProviderProperties {

    /** The reserved name of the primary provider used by the main agent conversation. */
    public static final String DEFAULT_PROVIDER_NAME = "default";

    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public static class ProviderConfig {
        /** One of {@code openai}, {@code anthropic}, {@code ollama}, {@code google.genai}. */
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String model;

        public ProviderConfig() {
        }

        public ProviderConfig(String provider, String apiKey, String baseUrl, String model) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
