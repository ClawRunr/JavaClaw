package ai.javaclaw.providers.openai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAIAgentOnboardingProviderTest {

    private OpenAIAgentOnboardingProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAIAgentOnboardingProvider(builder);
    }

    @Test
    void availableModelsFetchesModelsFromOpenAi() {
        server.expect(requestTo("https://api.openai.com/v1/models"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"id":"gpt-4o"},
                          {"id":"gpt-4o-mini"}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels(null, "sk-test"))
                .hasValueSatisfying(models -> assertThat(models)
                        .containsExactly("gpt-4o", "gpt-4o-mini"));
    }

    @Test
    void availableModelsUsesConfiguredBaseUrl() {
        server.expect(requestTo("https://gateway.example.com/v1/models"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"gpt-4o\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels("https://gateway.example.com", "sk-test"))
                .hasValueSatisfying(models -> assertThat(models).containsExactly("gpt-4o"));
    }

    @Test
    void availableModelsReturnsEmptyWhenKeyMissing() {
        assertThat(provider.availableModels(null, null)).isEmpty();
        assertThat(provider.availableModels(null, "")).isEmpty();
    }
}
