package ai.javaclaw.approval;

import java.util.Map;

/**
 * Declares the {@link ToolRisk} of tools by name. Plugins that contribute tools register one of
 * these as a bean next to the tools themselves; tools nobody classifies need approval.
 */
@FunctionalInterface
public interface ToolRiskContributor {

    /** Tool name, exactly as the model sees it, to its risk. */
    Map<String, ToolRisk> toolRisks();
}
