package ai.javaclaw.channels.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotFrameworkTokenProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private TeamsProperties properties() {
        TeamsProperties props = new TeamsProperties();
        props.setAppId("app-1");
        props.setAppSecret("secret-1");
        props.setTenantId("tenant-1");
        return props;
    }

    @Test
    void fetchesAndCachesToken() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"abc\",\"expires_in\":3600}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        BotFrameworkTokenProvider provider = new BotFrameworkTokenProvider(properties(), httpClient);

        assertThat(provider.getToken()).isEqualTo("abc");
        assertThat(provider.getToken()).isEqualTo("abc");
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void refetchesTokenAfterExpiry() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn("{\"access_token\":\"abc\",\"expires_in\":0}")
                .thenReturn("{\"access_token\":\"def\",\"expires_in\":3600}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        BotFrameworkTokenProvider provider = new BotFrameworkTokenProvider(properties(), httpClient);

        assertThat(provider.getToken()).isEqualTo("abc");
        assertThat(provider.getToken()).isEqualTo("def");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void throwsWhenTokenEndpointFails() throws Exception {
        when(httpResponse.statusCode()).thenReturn(401);
        // Lenient, because the body of a failed response must not be read into the error
        lenient().when(httpResponse.body()).thenReturn("invalid_client");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        BotFrameworkTokenProvider provider = new BotFrameworkTokenProvider(properties(), httpClient);

        assertThatThrownBy(provider::getToken)
                .isInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessage("Bot Framework token request failed with status 401");
    }

    @Test
    void formEncodesTheClientSecret() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"abc\",\"expires_in\":3600}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        TeamsProperties props = properties();
        props.setAppSecret("a+b&c=d/e");

        new BotFrameworkTokenProvider(props, httpClient).getToken();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://login.microsoftonline.com/tenant-1/oauth2/v2.0/token");
        assertThat(bodyOf(request.getValue())).isEqualTo("grant_type=client_credentials"
                + "&client_id=app-1"
                + "&client_secret=a%2Bb%26c%3Dd%2Fe"
                + "&scope=https%3A%2F%2Fapi.botframework.com%2F.default");
    }

    @Test
    void failsWithoutCallingTheTokenEndpointWhenTheTenantIdIsBlank() {
        TeamsProperties props = properties();
        props.setTenantId("  ");

        BotFrameworkTokenProvider provider = new BotFrameworkTokenProvider(props, httpClient);

        assertThatThrownBy(provider::getToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-id");
        verifyNoInteractions(httpClient);
    }

    private static String bodyOf(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
