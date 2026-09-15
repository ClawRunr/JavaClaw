package ai.javaclaw.providers.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnthropicAgentOnboardingProviderTest {

    private AnthropicAgentOnboardingProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicAgentOnboardingProvider(builder);
    }

    @Test
    void availableModelsFetchesModelsFromAnthropic() {
        server.expect(requestTo("https://api.anthropic.com/v1/models"))
                .andExpect(header("x-api-key", "sk-ant-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"id":"claude-opus-4-1"},
                          {"id":"claude-sonnet-4-6"}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels(null, "sk-ant-test"))
                .containsExactly("claude-opus-4-1", "claude-sonnet-4-6");
    }

    @Test
    void availableModelsUsesConfiguredBaseUrl() {
        server.expect(requestTo("https://anthropic.proxy.example.com/v1/models"))
                .andExpect(header("x-api-key", "sk-ant-test"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"claude-sonnet-4-6\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels("https://anthropic.proxy.example.com", "sk-ant-test"))
                .containsExactly("claude-sonnet-4-6");
    }

    @Test
    void availableModelsReturnsEmptyWhenKeyMissing() {
        assertThat(provider.availableModels(null, null)).isEmpty();
    }
}
