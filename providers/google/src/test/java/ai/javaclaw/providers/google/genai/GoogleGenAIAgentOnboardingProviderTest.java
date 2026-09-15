package ai.javaclaw.providers.google.genai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleGenAIAgentOnboardingProviderTest {

    private GoogleGenAIAgentOnboardingProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new GoogleGenAIAgentOnboardingProvider(builder);
    }

    @Test
    void availableModelsFetchesAndFiltersGenerateContentModels() {
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models?key=AIza-test"))
                .andRespond(withSuccess(
                        """
                        {"models":[
                          {"name":"models/gemini-1.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                          {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels(null, "AIza-test"))
                .hasValueSatisfying(models -> assertThat(models)
                        .containsExactly("gemini-1.5-flash"));
    }

    @Test
    void availableModelsUsesConfiguredBaseUrl() {
        server.expect(requestTo("https://gemini.proxy.example.com/v1beta/models?key=AIza-test"))
                .andRespond(withSuccess(
                        "{\"models\":[{\"name\":\"models/gemini-1.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.availableModels("https://gemini.proxy.example.com", "AIza-test"))
                .hasValueSatisfying(models -> assertThat(models).containsExactly("gemini-1.5-flash"));
    }

    @Test
    void availableModelsReturnsEmptyWhenKeyMissing() {
        assertThat(provider.availableModels(null, null)).isEmpty();
    }
}
