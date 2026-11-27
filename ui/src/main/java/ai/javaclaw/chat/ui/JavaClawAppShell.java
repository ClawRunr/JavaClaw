package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * Page-level settings for every Vaadin view.
 *
 * <p>Vaadin 25 no longer applies a theme on its own; without this the components render with only
 * their bare structural styles. It loads Vaadin's stock Lumo theme and the chat's layout sheet -
 * JavaClaw's own look, light and dark, is a separate step.
 *
 * <p>The icons are the ClawRunr mascot from the app module's static resources under {@code /brand},
 * the same files the HTMX pages link; the paths are absolute because Vaadin is mapped to {@code /ui}.
 */
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("/styles/chat.css")
public class JavaClawAppShell implements AppShellConfigurator {

    /** Vaadin creates the shell itself; it carries no state, only the annotation above. */
    public JavaClawAppShell() {
    }

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addFavIcon("icon", "/brand/favicon.ico", "16x16 32x32 48x48");
        settings.addFavIcon("icon", "/brand/icon-192.png", "192x192");
        settings.addFavIcon("icon", "/brand/icon-512.png", "512x512");
        settings.addFavIcon("apple-touch-icon", "/brand/apple-touch-icon.png", "180x180");
    }
}
