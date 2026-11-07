package ai.javaclaw.chat.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Refuses a WebSocket handshake that carries no {@code Origin} header.
 *
 * <p>Spring's own origin check treats a missing {@code Origin} as same-origin and lets it through.
 * Browsers always send one on a WebSocket handshake, so a request without it is not the web chat;
 * since the socket can answer tool approvals, it is refused rather than trusted.
 */
class RequireOriginInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequireOriginInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request.getHeaders().getOrigin() != null) {
            return true;
        }
        log.warn("Refused a web chat WebSocket handshake without an Origin header from {}", request.getRemoteAddress());
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
