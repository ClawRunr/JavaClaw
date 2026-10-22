package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * Page-level settings for every Vaadin view.
 *
 * <p>Vaadin 25 no longer applies a theme on its own; without this the components render with only
 * their bare structural styles. It loads Vaadin's stock Lumo theme and nothing else - JavaClaw's own
 * look, light and dark, is a separate step.
 */
@StyleSheet(Lumo.STYLESHEET)
public class JavaClawAppShell implements AppShellConfigurator {

    /** Vaadin creates the shell itself; it carries no state, only the annotation above. */
    public JavaClawAppShell() {
    }
}
