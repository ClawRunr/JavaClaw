package ai.javaclaw.chat.ws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WebOriginProperties.class)
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final WebOriginProperties origins;

    public WebSocketConfig(ChatWebSocketHandler handler, WebOriginProperties origins) {
        this.handler = handler;
        this.origins = origins;
    }

    /**
     * The chat socket answers tool approvals, so only JavaClaw's own pages may open it. Spring's
     * origin check allows the same origin (the scheme, host and port the request was made to) and
     * the configured {@code agent.web.allowed-origins}; a handshake without an {@code Origin}
     * header, which Spring would let through, is refused first.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(new RequireOriginInterceptor())
                .setAllowedOrigins(origins.allowedOrigins().toArray(String[]::new));
    }
}
