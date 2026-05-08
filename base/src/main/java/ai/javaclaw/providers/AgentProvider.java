package ai.javaclaw.providers;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.SequencedCollection;

@Component
public class AgentProvider {

    private final Environment environment;
    private final SequencedCollection<AgentOnboardingProvider> agentOnboardingProviders;
    private final SequencedCollection<ChatModel> chatModelProviders;

    public AgentProvider(Environment environment, SequencedCollection<AgentOnboardingProvider> agentOnboardingProviders, SequencedCollection<ChatModel> chatModelProviders) {
        this.environment = environment;
        this.agentOnboardingProviders = agentOnboardingProviders;
        this.chatModelProviders = chatModelProviders.stream().filter(this::isConfigured).toList();
    }

    private boolean isConfigured(ChatModel chatModel) {
        return false;
    }

    public ChatModel getDefaultChatModel() {
        return chatModelProviders.getFirst();
    }
}
