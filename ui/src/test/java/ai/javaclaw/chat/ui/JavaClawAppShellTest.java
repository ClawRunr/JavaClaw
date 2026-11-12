package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.page.Push;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaClawAppShellTest {

    /**
     * Vaadin registers an Atmosphere WebSocket at {@code /ui/VAADIN/push} whether or not push is on.
     * The UI itself does not use push: it answers over ordinary requests carrying Vaadin's CSRF
     * token, and cannot answer tool approvals. Turning push on would put that socket to use, so it
     * should be a deliberate change, not a side effect.
     */
    @Test
    void theVaadinUiDoesNotUseServerPush() {
        assertThat(JavaClawAppShell.class.getAnnotation(Push.class)).isNull();
    }
}
