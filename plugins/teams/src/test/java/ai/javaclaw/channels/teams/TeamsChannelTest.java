package ai.javaclaw.channels.teams;

import ai.javaclaw.channels.ChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamsChannelTest {

    @Mock
    private BotFrameworkTokenProvider tokenProvider;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private ChannelRegistry channelRegistry;

    private TeamsChannel channel;

    @BeforeEach
    void setUp() {
        channel = new TeamsChannel(tokenProvider, httpClient, channelRegistry);
    }

    @Test
    void registersItselfAsChannelOnConstruction() {
        verify(channelRegistry).registerChannel(channel);
        assertThat(channel.getName()).isEqualTo("teams");
    }

    @Test
    void sendMessageDoesNothingWhenNoConversationKnownYet() {
        // No activity has been received yet, so there is no known conversation to reply to
        channel.sendMessage("hello");

        verifyNoInteractions(httpClient);
    }

    @Test
    void sendMessageDoesNothingForBlankMessage() {
        channel.updateConversationReference(
                new TeamsConversationReference("https://smba.trafficmanager.net/amer/", "conv-1"));

        channel.sendMessage("   ");

        verifyNoInteractions(httpClient);
    }

    @Test
    void sendMessagePostsActivityToConnectorApiWithBearerToken() throws Exception {
        when(tokenProvider.getToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        channel.updateConversationReference(
                new TeamsConversationReference("https://smba.trafficmanager.net/amer", "conv-1"));

        channel.sendMessage("hi there");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest sent = captor.getValue();

        assertThat(sent.uri().toString())
                .isEqualTo("https://smba.trafficmanager.net/amer/v3/conversations/conv-1/activities");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer tok-123");
    }

    @Test
    void sendMessageDoesNotThrowWhenConnectorApiReturnsError() throws Exception {
        when(tokenProvider.getToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("boom");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        channel.updateConversationReference(new TeamsConversationReference("https://smba.example/", "conv-1"));

        channel.sendMessage("hi there"); // must be handled internally (logged), never thrown
    }

    @Test
    void sendMessageWithReferencePostsToThatConversationNotTheLastKnownOne() throws Exception {
        when(tokenProvider.getToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        channel.updateConversationReference(new TeamsConversationReference("https://smba.example/", "latest-conv"));

        channel.sendMessage(new TeamsConversationReference("https://smba.example/", "original-conv"), "hi there");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captor.getValue().uri().toString())
                .isEqualTo("https://smba.example/v3/conversations/original-conv/activities");
    }

    @Test
    void sendMessageWithReferenceDoesNothingForBlankMessage() {
        channel.sendMessage(new TeamsConversationReference("https://smba.example/", "conv-1"), " ");

        verifyNoInteractions(httpClient, tokenProvider);
    }

    @Test
    void markdownIsPosted() throws Exception {
        when(tokenProvider.getToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        TeamsConversationReference reference = new TeamsConversationReference("https://smba.example/", "conv-1");

        channel.sendMarkdown(reference, "**Approval needed**");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest post = requests.getValue();
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.uri()).hasToString("https://smba.example/v3/conversations/conv-1/activities");
        assertThat(bodyOf(post)).contains("\"textFormat\":\"markdown\"").contains("**Approval needed**");
    }

    @Test
    void aRefusedMarkdownMessageIsAnErrorSoTheApprovalIsNotLeftUnseen() throws Exception {
        when(tokenProvider.getToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(403);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() ->
                channel.sendMarkdown(new TeamsConversationReference("https://smba.example/", "conv-1"), "x"));
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        java.util.concurrent.CompletableFuture<String> body = new java.util.concurrent.CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                text.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                body.complete(text.toString());
            }
        });
        return body.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
