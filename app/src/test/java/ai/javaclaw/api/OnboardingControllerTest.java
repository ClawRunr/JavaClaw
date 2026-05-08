package ai.javaclaw.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import ai.javaclaw.onboarding.api.OnboardingController;
import ai.javaclaw.onboarding.steps.S1_WelcomeStep;
import ai.javaclaw.onboarding.steps.S2_ProviderStep;
import ai.javaclaw.onboarding.steps.S3_CredentialsStep;
import ai.javaclaw.onboarding.steps.S4_AgentMdStep;
import ai.javaclaw.onboarding.steps.S6_CompleteStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = OnboardingController.class, properties = {"agent.workspace=file:../workspace"})
@Import({S1_WelcomeStep.class, S2_ProviderStep.class, S3_CredentialsStep.class, S4_AgentMdStep.class, S6_CompleteStep.class})
class OnboardingControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
}