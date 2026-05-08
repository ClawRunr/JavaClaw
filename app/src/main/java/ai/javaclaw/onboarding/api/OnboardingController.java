package ai.javaclaw.onboarding.api;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import ai.javaclaw.onboarding.OnboardingProvider;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class OnboardingController {

    private static final String COMPLETE_STEP_ID = "complete";
    private static final String ONBOARDING_TEMPLATE = "onboarding/index";

    private final AgentOnboardingProviders agentOnboardingProviders;
    private final ConfigurationManager configurationManager;
    private final List<OnboardingProvider> steps;

    public OnboardingController(AgentOnboardingProviders agentOnboardingProviders, ConfigurationManager configurationManager, List<OnboardingProvider> steps) {
        this.agentOnboardingProviders = agentOnboardingProviders;
        this.configurationManager = configurationManager;
        this.steps = steps;
    }

    @GetMapping({"/onboarding", "/onboarding/"})
    public String onboarding() {
        return "redirect:/onboarding/" + steps.getFirst().getStepId();
    }

    @GetMapping("/onboarding/{stepId}")
    public String getStep(@PathVariable String stepId, HttpSession session, Model model) {
        OnboardingProvider onboardingProvider = findOnboardingProvider(stepId);
        if (onboardingProvider == null) {
            return "redirect:/onboarding/" + steps.getFirst().getStepId();
        }

        // When arriving at the complete step via GET (e.g. by skipping the last optional step),
        // save configuration if the session still holds onboarding data.
        if (COMPLETE_STEP_ID.equals(stepId) && session.getAttribute("onboarding.provider") != null) {
            String providerLabel = agentOnboardingProviders.getById((String) session.getAttribute("onboarding.provider")).getLabel();
            saveAndComplete(session);
            if (providerLabel != null) model.addAttribute("providerLabel", providerLabel);
        }

        Map<String, Object> sessionMap = sessionToMap(session);
        Map<String, Object> stepModel = new HashMap<>(model.asMap());
        onboardingProvider.prepareModel(sessionMap, stepModel);
        stepModel.forEach(model::addAttribute);

        int idx = stepIndex(stepId);
        model.addAttribute("currentStep", onboardingProvider.getStepTitle());
        model.addAttribute("currentStepNumber", idx + 1);
        model.addAttribute("totalSteps", steps.size());
        model.addAttribute("steps", steps.stream().map(p -> Map.of("title", p.getStepTitle(), "optional", p.isOptional())).toList());
        model.addAttribute("previousStepUrl", idx > 0 ? "/onboarding/" + steps.get(idx - 1).getStepId() : null);
        model.addAttribute("nextStepUrl", idx < steps.size() - 1 ? "/onboarding/" + steps.get(idx + 1).getStepId() : null);
        model.addAttribute("isOptional", onboardingProvider.isOptional());
        model.addAttribute("stepTemplate", onboardingProvider.getTemplatePath());
        return ONBOARDING_TEMPLATE;
    }

    @PostMapping("/onboarding/{stepId}")
    public String postStep(@PathVariable String stepId, @RequestParam Map<String, String> formParams, HttpSession session, RedirectAttributes redirectAttrs) {
        OnboardingProvider provider = findOnboardingProvider(stepId);
        if (provider == null) {
            return "redirect:/onboarding/" + steps.getFirst().getStepId();
        }

        Map<String, Object> sessionMap = sessionToMap(session);
        String error = provider.processStep(formParams, sessionMap);
        syncSessionFromMap(session, sessionMap);

        if (error != null) {
            redirectAttrs.addFlashAttribute("error", error);
            return "redirect:/onboarding/" + stepId;
        }

        String nextId = nextStepId(stepId);
        if (COMPLETE_STEP_ID.equals(nextId)) {
            String providerLabel = agentOnboardingProviders.getById((String) session.getAttribute("onboarding.provider")).getLabel();
            if (providerLabel != null) redirectAttrs.addFlashAttribute("providerLabel", providerLabel);
            saveAndComplete(session);
        }

        return "redirect:/onboarding/" + (nextId != null ? nextId : COMPLETE_STEP_ID);
    }

    private void saveAndComplete(HttpSession session) {
        Map<String, Object> finalSession = sessionToMap(session);
        try {
            for (OnboardingProvider p : steps) {
                p.saveConfiguration(finalSession, configurationManager);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save onboarding configuration", e);
        }
        clearOnboardingSession(session);
    }

    private OnboardingProvider findOnboardingProvider(String stepId) {
        return steps.stream()
                .filter(p -> p.getStepId().equals(stepId))
                .findFirst()
                .orElse(null);
    }

    private int stepIndex(String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getStepId().equals(stepId)) return i;
        }
        return 0;
    }

    private String nextStepId(String stepId) {
        int idx = stepIndex(stepId);
        return idx < steps.size() - 1 ? steps.get(idx + 1).getStepId() : null;
    }

    private Map<String, Object> sessionToMap(HttpSession session) {
        Map<String, Object> map = new HashMap<>();
        Collections.list(session.getAttributeNames()).forEach(name -> map.put(name, session.getAttribute(name)));
        return map;
    }

    private void syncSessionFromMap(HttpSession session, Map<String, Object> map) {
        map.forEach(session::setAttribute);
    }

    private void clearOnboardingSession(HttpSession session) {
        Collections.list(session.getAttributeNames()).stream()
                .filter(name -> name.startsWith("onboarding."))
                .toList()
                .forEach(session::removeAttribute);
    }
}