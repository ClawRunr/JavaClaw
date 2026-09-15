package ai.javaclaw.providers.ollama;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaAgentOnboardingProviderTest {

    private OllamaAgentOnboardingProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OllamaAgentOnboardingProvider(builder);
    }

    @Test
    void availableModelsFetchesTagsFromOllama() {
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withSuccess(
                        """
                        {"models":[
                          {"name":"llama3.2:latest","model":"llama3.2:latest"},
                          {"name":"qwen3.5:27b","model":"qwen3.5:27b"}
                        ]}
                        """,
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels(null, null))
                .hasValueSatisfying(models -> assertThat(models)
                        .containsExactly("llama3.2:latest", "qwen3.5:27b"));
    }

    @Test
    void availableModelsUsesConfiguredBaseUrl() {
        server.expect(requestTo("http://ollama.local:11434/api/tags"))
                .andRespond(withSuccess(
                        "{\"models\":[{\"name\":\"llama3.2:latest\",\"model\":\"llama3.2:latest\"}]}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels("http://ollama.local:11434/", null))
                .hasValueSatisfying(models -> assertThat(models).containsExactly("llama3.2:latest"));
    }

    @Test
    void availableModelsReturnsEmptyOnError() {
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        assertThat(provider.availableModels(null, null)).isEmpty();
    }
}
