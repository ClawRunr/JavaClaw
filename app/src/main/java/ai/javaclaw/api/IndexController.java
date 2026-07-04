package ai.javaclaw.api;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {

    private final Environment environment;

    public IndexController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping({"/", "/index"})
    public String index() {
        String provider = environment.getProperty("spring.ai.model.chat", "unknown");
        boolean onboardingCompleted = environment.getProperty("agent.onboarding.completed", Boolean.class, false);
        if (!"unknown".equals(provider) || onboardingCompleted) {
            return "redirect:/chat";
        }
        return "redirect:/onboarding/";
    }

    @GetMapping("/settings/agents")
    public String agents() {
        return "settings/agents";
    }
}
