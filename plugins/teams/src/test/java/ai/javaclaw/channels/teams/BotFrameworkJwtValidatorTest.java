package ai.javaclaw.channels.teams;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static ai.javaclaw.channels.teams.BotFrameworkJwtValidator.EXPECTED_ISSUER;
import static org.assertj.core.api.Assertions.assertThat;

class BotFrameworkJwtValidatorTest {

    private static final String APP_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SERVICE_URL = "https://smba.trafficmanager.net/amer/";

    // The "Bot Framework" key whose public half the validator trusts, and a key it has never seen
    private static RSAKey trustedKey;
    private static RSAKey unknownKey;

    private BotFrameworkJwtValidator validator;

    @BeforeAll
    static void generateKeys() throws JOSEException {
        trustedKey = new RSAKeyGenerator(2048).keyID("trusted").generate();
        unknownKey = new RSAKeyGenerator(2048).keyID("trusted").generate();
    }

    @BeforeEach
    void setUp() {
        TeamsProperties properties = new TeamsProperties();
        properties.setAppId(APP_ID);
        validator = new BotFrameworkJwtValidator(properties, new ImmutableJWKSet<>(new JWKSet(trustedKey.toPublicJWK())));
    }

    // -----------------------------------------------------------------------
    // Valid tokens
    // -----------------------------------------------------------------------

    @Test
    void acceptsTokenWithCorrectIssuerAudienceExpiryAndServiceUrl() throws Exception {
        String token = sign(trustedKey, validClaims().build());

        assertThat(validator.isValid(token, SERVICE_URL)).isTrue();
    }

    @Test
    void acceptsServiceUrlThatOnlyDiffersByTrailingSlash() throws Exception {
        String token = sign(trustedKey, validClaims().build());

        assertThat(validator.isValid(token, "https://smba.trafficmanager.net/amer")).isTrue();
    }

    @Test
    void acceptsTokenThatExpiredWithinTheAllowedClockSkew() throws Exception {
        String token = sign(trustedKey, validClaims().expirationTime(secondsFromNow(-60)).build());

        assertThat(validator.isValid(token, SERVICE_URL)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Claims
    // -----------------------------------------------------------------------

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = sign(trustedKey, validClaims().audience("someone-elses-app-id").build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        String token = sign(trustedKey, validClaims().issuer("https://evil.example.com").build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsTokenExpiredLongerThanTheAllowedClockSkew() throws Exception {
        String token = sign(trustedKey, validClaims().expirationTime(secondsFromNow(-3_600)).build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsTokenWithoutExpiry() throws Exception {
        String token = sign(trustedKey, validClaims().expirationTime(null).build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsTokenIssuedForAnotherServiceUrl() throws Exception {
        String token = sign(trustedKey, validClaims().build());

        assertThat(validator.isValid(token, "https://attacker.example.com/")).isFalse();
    }

    @Test
    void rejectsTokenWithoutServiceUrlClaim() throws Exception {
        String token = sign(trustedKey, validClaims().claim(BotFrameworkJwtValidator.SERVICE_URL_CLAIM, null).build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsWhenActivityHasNoServiceUrl() throws Exception {
        String token = sign(trustedKey, validClaims().build());

        assertThat(validator.isValid(token, null)).isFalse();
    }

    @Test
    void rejectsEveryTokenWhenNoAppIdIsConfigured() throws Exception {
        BotFrameworkJwtValidator withoutAppId = new BotFrameworkJwtValidator(
                new TeamsProperties(), new ImmutableJWKSet<>(new JWKSet(trustedKey.toPublicJWK())));
        String token = sign(trustedKey, validClaims().build());

        assertThat(withoutAppId.isValid(token, SERVICE_URL)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Signature
    // -----------------------------------------------------------------------

    @Test
    void rejectsTokenSignedWithAnUnknownKey() throws Exception {
        String token = sign(unknownKey, validClaims().build());

        assertThat(validator.isValid(token, SERVICE_URL)).isFalse();
    }

    @Test
    void rejectsMalformedToken() {
        assertThat(validator.isValid("not-a-jwt", SERVICE_URL)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(EXPECTED_ISSUER)
                .audience(APP_ID)
                .expirationTime(secondsFromNow(3_600))
                .claim(BotFrameworkJwtValidator.SERVICE_URL_CLAIM, SERVICE_URL);
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Date secondsFromNow(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1_000);
    }
}
