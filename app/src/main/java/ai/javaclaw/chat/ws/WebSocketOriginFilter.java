package ai.javaclaw.chat.ws;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * Checks the {@code Origin} of every WebSocket handshake JavaClaw serves, before any endpoint sees it.
 *
 * <p>The web chat socket ({@code /ws/chat}) answers tool approvals, and Vaadin registers an
 * Atmosphere push socket ({@code /ui/VAADIN/push}) whether or not its UI uses push. Checking here,
 * in front of both and of any socket added later, means no page from another site can open one
 * from the owner's browser: only JavaClaw's own origin (the scheme, host and port the request was
 * made to) and {@code agent.web.allowed-origins} are let through, and a handshake without an
 * {@code Origin} header is refused. Ordinary HTTP requests are not affected.
 */
class WebSocketOriginFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketOriginFilter.class);

    private final CorsConfiguration allowed = new CorsConfiguration();

    WebSocketOriginFilter(WebOriginProperties origins) {
        origins.allowedOrigins().forEach(allowed::addAllowedOrigin);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"websocket".equalsIgnoreCase(request.getHeader(HttpHeaders.UPGRADE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && (WebUtils.isSameOrigin(new ServletServerHttpRequest(request))
                || allowed.checkOrigin(origin) != null)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Refused a WebSocket handshake to {} from origin {}", request.getRequestURI(),
                origin == null ? "(none)" : origin);
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
