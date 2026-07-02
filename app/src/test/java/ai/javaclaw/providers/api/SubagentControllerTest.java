package ai.javaclaw.providers.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentStore;
import ai.javaclaw.llm.SubagentStore.Subagent;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubagentController.class)
class SubagentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubagentStore store;

    @MockitoBean
    private AgentOnboardingProviders providers;

    @MockitoBean
    private LlmProviderProperties providerProperties;

    @MockitoBean
    private ConfigurationManager configurationManager;

    @BeforeEach
    void setUp() {
        AgentOnboardingProvider openai = Mockito.mock(AgentOnboardingProvider.class);
        when(openai.getId()).thenReturn("openai");
        when(openai.getLabel()).thenReturn("OpenAI");
        when(openai.defaultModel()).thenReturn("gpt-4o");
        when(providers.getAll()).thenReturn(List.of(openai));
        when(providers.findById("openai")).thenReturn(Optional.of(openai));
        when(providers.findById("ghost")).thenReturn(Optional.empty());
        when(providerProperties.getProviders()).thenReturn(new java.util.LinkedHashMap<>());
    }

    @Test
    void providerOptionsComeFromOnboardingProviders() throws Exception {
        mockMvc.perform(get("/api/agents/options/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("openai"))
                .andExpect(jsonPath("$[0].label").value("OpenAI"))
                .andExpect(jsonPath("$[0].defaultModel").value("gpt-4o"));
    }

    @Test
    void listMergesProviderConfigWithMdDescription() throws Exception {
        when(store.list()).thenReturn(List.of(new Subagent("summariser", "summariser", "Summarises", "body")));
        when(providerProperties.getProviders()).thenReturn(Map.of(
                "summariser", new ProviderConfig("openai", "sk-test", null, "gpt-4o")));

        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("summariser"))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].providerLabel").value("OpenAI"))
                .andExpect(jsonPath("$[0].model").value("gpt-4o"));
    }

    @Test
    void createWritesMdFileAndProviderConfig() throws Exception {
        when(store.exists("summariser")).thenReturn(false);

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"summariser\",\"provider\":\"openai\",\"model\":\"gpt-4o\",\"apiKey\":\"sk-secret\",\"description\":\"sums\",\"content\":\"do it\"}"))
                .andExpect(status().isCreated());

        verify(store).save(any(Subagent.class));
        // Structured fields (provider/model/api-key) are persisted to agent.llm.providers.summariser.
        verify(configurationManager).updateProperties(argThat(m ->
                "openai".equals(m.get("agent.llm.providers.summariser.provider"))
                        && "gpt-4o".equals(m.get("agent.llm.providers.summariser.model"))
                        && "sk-secret".equals(m.get("agent.llm.providers.summariser.api-key"))));
    }

    @Test
    void createRejectsUnknownProvider() throws Exception {
        when(store.exists("summariser")).thenReturn(false);

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"summariser\",\"provider\":\"ghost\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(store, never()).save(any());
    }

    @Test
    void createRejectsNameAlreadyUsedByAProvider() throws Exception {
        when(providerProperties.getProviders()).thenReturn(Map.of(
                "default", new ProviderConfig("openai", "k", null, "gpt-4o")));

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"default\",\"provider\":\"openai\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(store, never()).save(any());
    }

    @Test
    void deleteRemovesMdAndProviderConfig() throws Exception {
        when(store.delete("summariser")).thenReturn(true);

        mockMvc.perform(delete("/api/agents/summariser"))
                .andExpect(status().isNoContent());

        verify(configurationManager).removeProperty("agent.llm.providers.summariser");
    }
}
