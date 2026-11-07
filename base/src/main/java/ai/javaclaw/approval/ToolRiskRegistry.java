package ai.javaclaw.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Looks up a tool's {@link ToolRisk}, merged from every {@link ToolRiskContributor}. */
public class ToolRiskRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRiskRegistry.class);

    private final Map<String, ToolRisk> risks;

    public ToolRiskRegistry(List<ToolRiskContributor> contributors) {
        Map<String, ToolRisk> merged = new HashMap<>();
        for (ToolRiskContributor contributor : contributors) {
            contributor.toolRisks().forEach((name, risk) -> {
                ToolRisk previous = merged.get(name);
                // Two plugins disagreeing on a name keep the stricter answer rather than the last one loaded
                if (previous != null && previous != risk) {
                    log.warn("Tool '{}' is classified both {} and {}; treating it as {}", name, previous, risk,
                            stricter(previous, risk));
                    risk = stricter(previous, risk);
                }
                merged.put(name, risk);
            });
        }
        this.risks = Map.copyOf(merged);
    }

    /** Empty when nobody classified the tool, which means it needs approval. */
    public Optional<ToolRisk> riskOf(String toolName) {
        return Optional.ofNullable(risks.get(toolName));
    }

    private static ToolRisk stricter(ToolRisk a, ToolRisk b) {
        return a == ToolRisk.READ_ONLY ? b : a;
    }
}
