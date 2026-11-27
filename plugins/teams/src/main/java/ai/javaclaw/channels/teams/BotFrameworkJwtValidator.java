package ai.javaclaw.channels.teams;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Validates the Bot Framework Connector JWT sent on every incoming Teams webhook call
 * (the {@code Authorization: Bearer <token>} header), per
 * https://learn.microsoft.com/azure/bot-service/rest-api/bot-framework-rest-connector-authentication
 * <p>
 * A valid token must: (1) be signed with RS256 by a key currently published in Bot Framework's JWKS,
 * (2) have issuer {@code https://api.botframework.com}, (3) have this bot's App ID as audience,
 * (4) not be expired, allowing five minutes of clock skew as Microsoft recommends, and (5) carry a
 * {@code serviceUrl} claim equal to the activity's {@code serviceUrl}. The last check matters because
 * the bot later sends its own outbound token to that URL. The JWKS is fetched once and
 * cached/refreshed automatically by the underlying {@link JWKSource}.
 */
class BotFrameworkJwtValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotFrameworkJwtValidator.class);
    static final String EXPECTED_ISSUER = "https://api.botframework.com";
    static final String SERVICE_URL_CLAIM = "serviceUrl";
    static final int MAX_CLOCK_SKEW_SECONDS = (int) Duration.ofMinutes(5).toSeconds();
    private static final String JWKS_URL = "https://login.botframework.com/v1/.well-known/keys";

    private final DefaultJWTProcessor<SecurityContext> jwtProcessor;

    BotFrameworkJwtValidator(TeamsProperties properties) {
        this(properties, remoteJwkSource());
    }

    /** Test seam: inject a local key set instead of hitting the real JWKS endpoint. */
    BotFrameworkJwtValidator(TeamsProperties properties, JWKSource<SecurityContext> jwkSource) {
        String appId = properties.getAppId();
        // An empty accepted-audience set rejects every token, which is what we want without an App ID
        Set<String> acceptedAudience = appId == null || appId.isBlank() ? Set.of() : Set.of(appId);

        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                acceptedAudience,
                new JWTClaimsSet.Builder().issuer(EXPECTED_ISSUER).build(),
                Set.of("exp", SERVICE_URL_CLAIM),
                null);
        claimsVerifier.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);

        this.jwtProcessor = new DefaultJWTProcessor<>();
        this.jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        this.jwtProcessor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    private static JWKSource<SecurityContext> remoteJwkSource() {
        try {
            return JWKSourceBuilder
                    .create(URI.create(JWKS_URL).toURL())
                    .cache(Duration.ofMinutes(30).toMillis(), Duration.ofMinutes(1).toMillis())
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid Bot Framework JWKS URL: " + JWKS_URL, e);
        }
    }

    /**
     * @param bearerToken the token from the {@code Authorization} header, without the {@code Bearer } prefix
     * @param serviceUrl  the {@code serviceUrl} of the incoming activity
     * @return true if {@code bearerToken} is a currently valid Bot Framework Connector token for this bot
     * that was issued for {@code serviceUrl}
     */
    boolean isValid(String bearerToken, String serviceUrl) {
        try {
            // Throws if the signature, algorithm, issuer, audience or expiry is wrong
            JWTClaimsSet claims = jwtProcessor.process(SignedJWT.parse(bearerToken), null);

            String tokenServiceUrl = claims.getStringClaim(SERVICE_URL_CLAIM);
            if (serviceUrl == null || !sameUrl(tokenServiceUrl, serviceUrl)) {
                LOGGER.warn("Rejected Teams webhook JWT issued for serviceUrl '{}' on an activity for '{}'",
                        tokenServiceUrl, serviceUrl);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Rejected Teams webhook JWT: {}", e.getMessage());
            return false;
        }
    }

    /** Compares two URLs, ignoring a trailing slash and letter case. */
    private static boolean sameUrl(String a, String b) {
        return a != null && stripTrailingSlash(a).equalsIgnoreCase(stripTrailingSlash(b));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
