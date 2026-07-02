package ai.javaclaw.providers.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentReferenceScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProviderController.class)
class ProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProviderProperties properties;

    @MockitoBean
    private ConfigurationManager configurationManager;

    @MockitoBean
    private SubagentReferenceScanner subagentScanner;

    @MockitoBean
    private ChatModelFactory chatModelFactory;

    @BeforeEach
    void setUp() {
        when(chatModelFactory.supports("openai")).thenReturn(true);
    }

    @Test
    void createPersistsProvider() throws Exception {
        when(properties.getProviders()).thenReturn(Map.of());

        mockMvc.perform(post("/api/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"local\",\"provider\":\"openai\",\"model\":\"gpt-4o\",\"apiKey\":\"sk-test\"}"))
                .andExpect(status().isCreated());

        verify(configurationManager).updateProperties(any());
    }

    @Test
    void createRejectsUnknownProviderType() throws Exception {
        when(properties.getProviders()).thenReturn(Map.of());

        mockMvc.perform(post("/api/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"local\",\"provider\":\"ghost\",\"model\":\"x\",\"apiKey\":\"k\"}"))
                .andExpect(status().isBadRequest());

        verify(configurationManager, never()).updateProperties(any());
    }

    @Test
    void deleteReturnsConflictWhenReferencedBySubagents() throws Exception {
        when(properties.getProviders()).thenReturn(Map.of("local", new ProviderConfig("openai", "k", null, "gpt-4o")));
        when(subagentScanner.namesReferencing("local")).thenReturn(Set.of("summariser", "classifier"));

        mockMvc.perform(delete("/api/providers/local"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.affected").isArray())
                .andExpect(jsonPath("$.affected.length()").value(2));

        verify(configurationManager, never()).removeProperty(any());
    }

    @Test
    void deleteWithForceSucceedsEvenWhenReferenced() throws Exception {
        when(properties.getProviders()).thenReturn(Map.of("local", new ProviderConfig("openai", "k", null, "gpt-4o")));
        when(subagentScanner.namesReferencing("local")).thenReturn(Set.of("summariser"));

        mockMvc.perform(delete("/api/providers/local").param("force", "true"))
                .andExpect(status().isNoContent());

        verify(configurationManager).removeProperty("agent.llm.providers.local");
    }
}
