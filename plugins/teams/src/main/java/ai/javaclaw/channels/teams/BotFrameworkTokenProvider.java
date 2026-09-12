package ai.javaclaw.channels.teams;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches and caches an OAuth2 client-credentials token for the Bot Framework
 * Connector API, using the Azure AD v2 endpoint (login.microsoftonline.com).
 */
class BotFrameworkTokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotFrameworkTokenProvider.class);
    private static final String SCOPE = "https://api.botframework.com/.default";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TeamsProperties properties;
    private final HttpClient httpClient;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    BotFrameworkTokenProvider(TeamsProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /** @return a valid bearer token, refreshing it if expired or missing */
    synchronized String getToken() {
        CachedToken token = cached.get();
        if (token != null && Instant.now().isBefore(token.expiresAt())) {
            return token.value();
        }
        CachedToken fresh = fetchToken();
        cached.set(fresh);
        return fresh.value();
    }

    private CachedToken fetchToken() {
        String tokenUrl = "https://login.microsoftonline.com/" + properties.getTenantId()
                + "/oauth2/v2.0/token";
        String body = "grant_type=client_credentials"
                + "&client_id=" + properties.getAppId()
                + "&client_secret=" + properties.getAppSecret()
                + "&scope=" + SCOPE;

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Bot Framework token request failed with status " + response.statusCode()
                                + ": " + response.body());
            }
            TokenResponse parsed = JSON.readValue(response.body(), TokenResponse.class);
            return new CachedToken(parsed.accessToken(),
                    Instant.now().plusSeconds(Math.max(0, parsed.expiresIn() - 60)));
        } catch (Exception e) {
            LOGGER.error("Failed to fetch Bot Framework access token", e);
            throw new IllegalStateException("Could not obtain Bot Framework access token", e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    private record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") int expiresIn) {
    }
}