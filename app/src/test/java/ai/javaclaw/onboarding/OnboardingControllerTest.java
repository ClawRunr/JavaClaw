package ai.javaclaw.onboarding;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.steps.S1_WelcomeStep;
import ai.javaclaw.onboarding.steps.S2_ProviderStep;
import ai.javaclaw.onboarding.steps.S3_CredentialsStep;
import ai.javaclaw.onboarding.steps.S4_AgentMdStep;
import ai.javaclaw.onboarding.steps.S6_CompleteStep;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = OnboardingController.class, properties = {"agent.workspace=file:../workspace", "agent.llm.providers.default.model="})
@Import({S1_WelcomeStep.class, S2_ProviderStep.class, S3_CredentialsStep.class, S4_AgentMdStep.class, S6_CompleteStep.class})
class OnboardingControllerTest {

    private static final String BASE_URL_KEY = "agent.llm.providers.default.base-url";

    private static final AgentOnboardingProvider OPENAI = new AgentOnboardingProvider() {
        @Override
        public String getId() {return "openai";}

        @Override
        public String getLabel() {return "OpenAI";}

        @Override
        public String slogan() {return "";}

        @Override
        public boolean requiresApiKey() {return true;}

        @Override
        public String defaultModel() {return "gpt-5.4";}
    };

    private static final AgentOnboardingProvider LISTED = new AgentOnboardingProvider() {
        @Override
        public String getId() {return "listed";}

        @Override
        public String getLabel() {return "Listed";}

        @Override
        public String slogan() {return "";}

        @Override
        public boolean requiresApiKey() {return false;}

        @Override
        public boolean supportsBaseUrl() {return false;}

        @Override
        public String defaultModel() {return "model-b";}

        @Override
        public List<ModelChoice> models() {
            return List.of(new ModelChoice("model-a", "Model A"), new ModelChoice("model-b", "Model B"));
        }
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private S2_ProviderStep providerStep;

    @MockitoBean
    private ConfigurationManager configurationManager;

    @MockitoBean
    private AgentOnboardingProviders agentOnboardingProviders;

    @Test
    void providerSubmissionWithoutSelectionShowsFlashError() throws Exception {
        mockMvc.perform(post("/onboarding/provider"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/onboarding/provider"))
                .andExpect(flash().attribute("error", "Choose one of the supported providers to continue."));
    }

    @Test
    void baseUrlEnteredOnCredentialsStepIsSavedAsProviderProperty() throws Exception {
        MockHttpSession session = openAiSessionThrough(" https://gateway.example.com/v1 ");

        assertThat(savedProperties(session)).containsEntry(BASE_URL_KEY, "https://gateway.example.com/v1");
    }

    @Test
    void blankBaseUrlRemovesAPreviouslySavedOne() throws Exception {
        MockHttpSession session = openAiSessionThrough("   ");

        assertThat(savedProperties(session)).containsEntry(BASE_URL_KEY, null);
    }

    @Test
    void savedValuesOfAnotherProviderAreNotOffered() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("agent.llm.providers.default.provider", "openai")
                .withProperty("agent.llm.providers.default.base-url", "https://proxy.example.com")
                .withProperty("agent.llm.providers.default.api-key", "sk-old")
                .withProperty("agent.llm.providers.default.model", "gpt-5.4");
        when(agentOnboardingProviders.findById(LISTED.getId())).thenReturn(Optional.of(LISTED));
        Map<String, Object> session = new HashMap<>(Map.of("onboarding.provider", LISTED.getId()));
        Map<String, Object> model = new HashMap<>();

        new S3_CredentialsStep(env, agentOnboardingProviders).prepareModel(session, model);

        assertThat(model).containsEntry("model", "model-b").containsEntry("baseUrl", "").containsEntry("apiKey", "");
        assertThat((List<?>) model.get("modelChoices")).hasSize(2);
    }

    @Test
    void savedValuesOfTheSameProviderAreOffered() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("agent.llm.providers.default.provider", "listed")
                .withProperty("agent.llm.providers.default.model", "model-a");
        when(agentOnboardingProviders.findById(LISTED.getId())).thenReturn(Optional.of(LISTED));
        Map<String, Object> model = new HashMap<>();

        new S3_CredentialsStep(env, agentOnboardingProviders).prepareModel(new HashMap<>(Map.of("onboarding.provider", "listed")), model);

        assertThat(model).containsEntry("model", "model-a");
    }

    @Test
    void providerWithAModelListOffersADropdownWithItsDefaultSelected() throws Exception {
        String page = credentialsPageFor(LISTED, new MockHttpSession());

        assertThat(page).contains("<select id=\"model\" name=\"model\">")
                .contains("<option value=\"model-a\">Model A</option>")
                .contains("<option value=\"model-b\" selected>Model B</option>")
                .doesNotContain("name=\"baseUrl\"");
    }

    @Test
    void aModelOutsideTheListStaysSelectable() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("onboarding.model", "legacy-alias");

        assertThat(credentialsPageFor(LISTED, session)).contains("<option value=\"legacy-alias\" selected>legacy-alias</option>");
    }

    @Test
    void providerWithoutAModelListKeepsTheTextField() throws Exception {
        assertThat(credentialsPageFor(OPENAI, new MockHttpSession()))
                .contains("<input id=\"model\" class=\"input is-medium\" type=\"text\" name=\"model\" value=\"gpt-5.4\"")
                .doesNotContain("<select id=\"model\"");
    }

    private String credentialsPageFor(AgentOnboardingProvider provider, MockHttpSession session) throws Exception {
        when(agentOnboardingProviders.getById(provider.getId())).thenReturn(provider);
        when(agentOnboardingProviders.findById(provider.getId())).thenReturn(Optional.of(provider));
        session.setAttribute("onboarding.provider", provider.getId());
        return mockMvc.perform(get("/onboarding/credentials").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Walks provider + credentials for OpenAI, submitting {@code baseUrl}, and returns the session.
     */
    private MockHttpSession openAiSessionThrough(String baseUrl) throws Exception {
        when(agentOnboardingProviders.getById("openai")).thenReturn(OPENAI);
        when(agentOnboardingProviders.findById("openai")).thenReturn(Optional.of(OPENAI));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/onboarding/provider").param("provider", "openai").session(session))
                .andExpect(redirectedUrl("/onboarding/credentials"));
        mockMvc.perform(post("/onboarding/credentials").session(session)
                        .param("model", "gpt-5.4")
                        .param("apiKey", "sk-test")
                        .param("baseUrl", baseUrl))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedProperties(MockHttpSession session) throws Exception {
        providerStep.saveConfiguration(sessionAsMap(session), configurationManager);
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(props.capture());
        return props.getValue();
    }

    private static Map<String, Object> sessionAsMap(MockHttpSession session) {
        return java.util.Collections.list(session.getAttributeNames()).stream()
                .collect(java.util.stream.Collectors.toMap(n -> n, session::getAttribute));
    }

}
