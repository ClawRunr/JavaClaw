package ai.javaclaw.agents;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import ai.javaclaw.llm.SubagentStore;
import ai.javaclaw.llm.SubagentStore.Subagent;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AgentPageController.class)
class AgentPageControllerTest {

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

    private AgentOnboardingProvider openai;

    @BeforeEach
    void setUp() {
        openai = org.mockito.Mockito.mock(AgentOnboardingProvider.class);
        when(openai.getId()).thenReturn("openai");
        when(openai.getLabel()).thenReturn("OpenAI");
        when(openai.defaultModel()).thenReturn("gpt-4o");
        when(providers.getAll()).thenReturn(List.of(openai));
        when(providers.findById("openai")).thenReturn(Optional.of(openai));
        when(providers.findById("ghost")).thenReturn(Optional.empty());
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>());
    }

    @Test
    void listFragmentRendersListView() throws Exception {
        when(store.list()).thenReturn(List.of(new Subagent("summariser", "summariser", "Summarises", "body")));
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>(Map.of(
                "summariser", new ProviderConfig("openai", null, null, "gpt-4o"))));

        mockMvc.perform(get("/settings/agents/fragments/list"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/list"))
                .andExpect(model().attributeExists("agents"));
    }

    @Test
    void newFormRendersDrawerView() throws Exception {
        mockMvc.perform(get("/settings/agents/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/drawer"))
                .andExpect(model().attribute("drawerTitle", "Add Agent"))
                .andExpect(model().attribute("isEdit", false))
                .andExpect(model().attribute("formAction", "/settings/agents"));
    }

    @Test
    void editFormRendersPrefilledDrawer() throws Exception {
        when(store.get("summariser")).thenReturn(Optional.of(
                new Subagent("summariser", "summariser", "Summarises", "do it")));
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>(Map.of(
                "summariser", new ProviderConfig("openai", "https://x", "sk-test", "gpt-4o"))));

        mockMvc.perform(get("/settings/agents/summariser/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/drawer"))
                .andExpect(model().attribute("drawerTitle", "Edit summariser"))
                .andExpect(model().attribute("isEdit", true))
                .andExpect(model().attribute("formAction", "/settings/agents/summariser"))
                .andExpect(model().attribute("nameReadonly", true))
                .andExpect(model().attribute("model", "gpt-4o"))
                .andExpect(model().attribute("baseUrl", "https://x"));
    }

    @Test
    void editUnknownAgentIsNotFound() throws Exception {
        when(store.get("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/settings/agents/ghost/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWritesMdFileAndProviderConfig() throws Exception {
        when(store.exists("summariser")).thenReturn(false);
        when(store.list()).thenReturn(List.of());

        mockMvc.perform(post("/settings/agents")
                        .param("name", "summariser")
                        .param("provider", "openai")
                        .param("baseUrl", "https://gateway.example.com/v1")
                        .param("apiKey", "sk-secret")
                        .param("model", "gpt-4o"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/list"));

        verify(store).save(any(Subagent.class));
        verify(configurationManager).updateProperties(argThat(m ->
                "openai".equals(m.get("agent.llm.providers.summariser.provider"))
                        && "https://gateway.example.com/v1".equals(m.get("agent.llm.providers.summariser.base-url"))
                        && "sk-secret".equals(m.get("agent.llm.providers.summariser.api-key"))
                        && "gpt-4o".equals(m.get("agent.llm.providers.summariser.model"))));
    }

    @Test
    void createValidationErrorRendersDrawerWith422AndRetarget() throws Exception {
        when(store.exists("summariser")).thenReturn(false);
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>());

        mockMvc.perform(post("/settings/agents")
                        .param("name", "summariser")
                        .param("provider", "ghost"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#agent-drawer"))
                .andExpect(view().name("settings/agents/drawer"))
                .andExpect(model().attribute("error", "Unknown provider: ghost"));
    }

    @Test
    void updateSuccessRendersList() throws Exception {
        when(store.exists("summariser")).thenReturn(true);
        when(store.list()).thenReturn(List.of());
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>());

        mockMvc.perform(put("/settings/agents/summariser")
                        .param("name", "summariser")
                        .param("provider", "openai")
                        .param("model", "gpt-4o"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/list"));

        verify(store).save(any(Subagent.class));
    }

    @Test
    void updateUnknownNameIsNotFound() throws Exception {
        when(store.exists("ghost")).thenReturn(false);

        mockMvc.perform(put("/settings/agents/ghost")
                        .param("name", "ghost")
                        .param("provider", "openai"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesMdAndProviderConfigAndRendersList() throws Exception {
        when(store.delete("summariser")).thenReturn(true);
        when(store.list()).thenReturn(List.of());
        when(providerProperties.getProviders()).thenReturn(new LinkedHashMap<>());

        mockMvc.perform(delete("/settings/agents/summariser"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/agents/list"));

        verify(configurationManager).removeProperty("agent.llm.providers.summariser");
    }
}