package ai.javaclaw.onboarding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

@Component
public class AgentOnboardingProviders {

    private final SequencedSet<AgentOnboardingProvider> agentOnboardingProviders;

    public AgentOnboardingProviders(SequencedSet<AgentOnboardingProvider> agentOnboardingProviders) {
        this.agentOnboardingProviders = agentOnboardingProviders;
    }

    public List<AgentOnboardingProvider> getAll() {
        return new ArrayList<>(agentOnboardingProviders);
    }

    public Optional<AgentOnboardingProvider> findById(String value) {
        return agentOnboardingProviders.stream()
                .filter(provider -> value.equalsIgnoreCase(provider.getId()))
                .findFirst();
    }

    public AgentOnboardingProvider getById(String value) {
        return findById(value).orElseThrow();
    }
}
