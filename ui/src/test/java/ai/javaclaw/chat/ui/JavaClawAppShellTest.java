package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.page.Push;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaClawAppShellTest {

    /**
     * The web chat's {@code /ws/chat} socket only accepts JavaClaw's own origin, because it can answer
     * tool approvals. Vaadin opens a WebSocket of its own only for server push, which this UI does not
     * use: it answers over ordinary requests that carry Vaadin's CSRF token. Turning push on would add
     * an Atmosphere socket that needs the same origin check before it ships.
     */
    @Test
    void theVaadinUiOpensNoWebSocketOfItsOwn() {
        assertThat(JavaClawAppShell.class.getAnnotation(Push.class)).isNull();
    }
}
