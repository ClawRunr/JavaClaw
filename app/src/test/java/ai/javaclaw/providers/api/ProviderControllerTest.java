package ai.javaclaw.providers.api;

import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProviderController.class)
class ProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProviderProperties properties;

    @Test
    void listReturnsConfiguredProvidersWithMaskedKey() throws Exception {
        when(properties.getProviders()).thenReturn(Map.of(
                "default", new ProviderConfig("openai", "sk-supersecretkey", null, "gpt-4o")));

        mockMvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("default"))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].model").value("gpt-4o"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].apiKeyMasked").value("sk-...tkey"));
    }
}
