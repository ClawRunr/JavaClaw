package ai.javaclaw.chat.ws;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The origin check in front of every WebSocket endpoint, including Vaadin's push socket at
 * {@code /ui/VAADIN/push}, which Vaadin registers whether or not its UI uses push.
 */
class WebSocketOriginFilterTest {

    final WebSocketOriginFilter filter =
            new WebSocketOriginFilter(new WebOriginProperties(List.of("https://claw.example.com")));

    @Test
    void javaClawsOwnOriginMayOpenAnySocket() throws Exception {
        assertThat(passes(upgrade("/ws/chat", "http://localhost:8080"))).isTrue();
        assertThat(passes(upgrade("/ui/VAADIN/push", "http://localhost:8080"))).isTrue();
    }

    @Test
    void anotherOriginIsRefusedOnEverySocket() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(passes(upgrade("/ui/VAADIN/push", "http://evil.example"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(passes(upgrade("/ws/chat", "http://evil.example"))).isFalse();
        // Same host, other port: another origin
        assertThat(passes(upgrade("/ws/chat", "http://localhost:3000"))).isFalse();
    }

    @Test
    void aHandshakeWithoutAnOriginIsRefused() throws Exception {
        assertThat(passes(upgrade("/ui/VAADIN/push", null))).isFalse();
    }

    @Test
    void aConfiguredOriginIsAccepted() throws Exception {
        assertThat(passes(upgrade("/ws/chat", "https://claw.example.com"))).isTrue();
    }

    @Test
    void ordinaryRequestsAreNotChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/teams/webhook");
        request.addHeader("Origin", "http://evil.example");
        assertThat(passes(request)).isTrue();
        assertThat(passes(new MockHttpServletRequest("GET", "/chat"))).isTrue();
    }

    private static MockHttpServletRequest upgrade(String path, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addHeader("Upgrade", "websocket");
        request.addHeader("Connection", "Upgrade");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private boolean passes(MockHttpServletRequest request) throws Exception {
        return passes(request, new MockHttpServletResponse());
    }

    private boolean passes(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }
}
