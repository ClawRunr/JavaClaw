package ai.javaclaw.channels.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(httpResponse.body()).thenReturn("invalid_client");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        BotFrameworkTokenProvider provider = new BotFrameworkTokenProvider(properties(), httpClient);

        assertThatThrownBy(provider::getToken).isInstanceOf(IllegalStateException.class);
    }
}