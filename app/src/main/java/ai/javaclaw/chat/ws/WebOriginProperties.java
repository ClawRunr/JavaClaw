package ai.javaclaw.chat.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Which browser origins may open the web chat's WebSocket, besides JavaClaw's own.
 *
 * <p>The socket can answer tool approvals, so a page from any other origin must not be able to
 * open it. JavaClaw's own origin (the scheme, host and port the request was made to) is always
 * allowed; list extra origins only when a proxy serves the chat under another host, for example
 * {@code https://claw.example.com}. A wildcard is refused at startup, since it would let every web
 * page in the browser answer approvals.
 *
 * @param allowedOrigins extra origins, as {@code scheme://host[:port]}; empty by default
 */
@ConfigurationProperties("agent.web")
public record WebOriginProperties(List<String> allowedOrigins) {

    public WebOriginProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(WebOriginProperties::validOrigin)
                .toList();
    }

    private static String validOrigin(String value) {
        String origin = value == null ? "" : value.trim();
        if (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        if (origin.contains("*")) {
            throw new IllegalArgumentException("agent.web.allowed-origins must list exact origins, not '"
                    + value + "': a wildcard would let any web page answer tool approvals");
        }
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean hasOnlyOriginParts = uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            if ((scheme.equals("http") || scheme.equals("https")) && hasOnlyOriginParts) {
                return origin;
            }
        } catch (URISyntaxException ignored) {
            // reported below
        }
        throw new IllegalArgumentException("agent.web.allowed-origins entry '" + value
                + "' is not an origin; expected scheme://host[:port], for example https://claw.example.com");
    }
}
