package ai.javaclaw.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ChatController {

    @Value("${jobrunr.dashboard.port:8081}")
    private int jobrunrDashboardPort;

    @GetMapping("/chat")
    public String chat(Model model) {
        model.addAttribute("jobrunrDashboardPort", jobrunrDashboardPort);
        return "chat";
    }
}
