package ai.javaclaw.channels.teams;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.JWTProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotFrameworkJwtValidatorTest {

    private static final String APP_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    JWTProcessor<SecurityContext> jwtProcessor;

    BotFrameworkJwtValidator validator;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        TeamsProperties properties = new TeamsProperties();
        properties.setAppId(APP_ID);
        validator = new BotFrameworkJwtValidator(properties, jwtProcessor);
        token = wellFormedButUnverifiedJwt(); // structurally valid; signature check is mocked below
    }

    private static String wellFormedButUnverifiedJwt() throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048).generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private JWTClaimsSet claims(String issuer, String audience, Date expiry) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .expirationTime(expiry)
                .build();
    }

    @Test
    void acceptsTokenWithCorrectIssuerAudienceAndExpiry() throws Exception {
        when(jwtProcessor.process(any(SignedJWT.class), isNull())).thenReturn(
                claims("https://api.botframework.com", APP_ID, future()));

        assertThat(validator.isValid(token)).isTrue();
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        when(jwtProcessor.process(any(SignedJWT.class), isNull())).thenReturn(
                claims("https://api.botframework.com", "someone-elses-app-id", future()));

        assertThat(validator.isValid(token)).isFalse();
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        when(jwtProcessor.process(any(SignedJWT.class), isNull())).thenReturn(
                claims("https://evil.example.com", APP_ID, future()));

        assertThat(validator.isValid(token)).isFalse();
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        when(jwtProcessor.process(any(SignedJWT.class), isNull())).thenReturn(
                claims("https://api.botframework.com", APP_ID, past()));

        assertThat(validator.isValid(token)).isFalse();
    }

    @Test
    void rejectsTokenWhoseSignatureFailsVerification() throws Exception {
        when(jwtProcessor.process(any(SignedJWT.class), isNull()))
                .thenThrow(new BadJWTException("signature verification failed"));

        assertThat(validator.isValid(token)).isFalse();
    }

    @Test
    void rejectsMalformedToken() {
        // "not-a-jwt" fails at SignedJWT.parse(...) itself, before jwtProcessor is ever touched
        assertThat(validator.isValid("not-a-jwt")).isFalse();
    }

    private static Date future() { return new Date(System.currentTimeMillis() + 3_600_000); }
    private static Date past() { return new Date(System.currentTimeMillis() - 3_600_000); }
}