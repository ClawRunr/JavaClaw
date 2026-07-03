package ai.javaclaw.providers.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the post-onboarding settings pages. The Agents page is a thin shell that talks to
 * {@link SubagentController} via fetch.
 */
@Controller
public class SettingsController {

    @GetMapping("/settings/agents")
    public String agents() {
        return "settings/agents";
    }
}
