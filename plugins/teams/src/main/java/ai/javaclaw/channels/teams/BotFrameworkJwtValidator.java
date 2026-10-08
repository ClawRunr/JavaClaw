package ai.javaclaw.channels.teams;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Date;

/**
 * Validates the Bot Framework Connector JWT sent on every incoming Teams webhook call
 * (the {@code Authorization: Bearer <token>} header), per
 * https://learn.microsoft.com/azure/bot-service/rest-api/bot-framework-rest-connector-authentication
 * <p>
 * A valid token must: (1) be signed by a key currently published in Bot Framework's JWKS,
 * (2) have issuer {@code https://api.botframework.com}, (3) have this bot's App ID as
 * audience, and (4) not be expired. The JWKS itself is fetched once and cached/refreshed
 * automatically by the underlying {@link JWKSource}.
 */
class BotFrameworkJwtValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotFrameworkJwtValidator.class);
    private static final String EXPECTED_ISSUER = "https://api.botframework.com";
    private static final String JWKS_URL = "https://login.botframework.com/v1/.well-known/keys";

    private final TeamsProperties properties;
    private final JWTProcessor<SecurityContext> jwtProcessor;

    BotFrameworkJwtValidator(TeamsProperties properties) {
        this(properties, defaultProcessor());
    }

    /** Test seam: inject a stub/mocked processor instead of hitting the real JWKS endpoint. */
    BotFrameworkJwtValidator(TeamsProperties properties, JWTProcessor<SecurityContext> jwtProcessor) {
        this.properties = properties;
        this.jwtProcessor = jwtProcessor;
    }

    private static JWTProcessor<SecurityContext> defaultProcessor() {
        try {
            JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                    .create(URI.create(JWKS_URL).toURL())
                    .cache(Duration.ofMinutes(30).toMillis(), Duration.ofMinutes(1).toMillis())
                    .build();
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
            return processor;
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid Bot Framework JWKS URL: " + JWKS_URL, e);
        }
    }

    /** @return true if {@code bearerToken} is a currently valid Bot Framework Connector token for this bot */
    boolean isValid(String bearerToken) {
        try {
            SignedJWT jwt = SignedJWT.parse(bearerToken);
            JWTClaimsSet claims = jwtProcessor.process(jwt, null); // throws if signature/algorithm is invalid

            if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
                LOGGER.warn("Rejected Teams webhook JWT with unexpected issuer '{}'", claims.getIssuer());
                return false;
            }
            if (properties.getAppId() == null || !claims.getAudience().contains(properties.getAppId())) {
                LOGGER.warn("Rejected Teams webhook JWT with unexpected audience {}", claims.getAudience());
                return false;
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                LOGGER.warn("Rejected expired Teams webhook JWT");
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Rejected Teams webhook JWT: {}", e.getMessage());
            return false;
        }
    }
}