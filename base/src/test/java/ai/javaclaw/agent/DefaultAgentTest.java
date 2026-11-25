package ai.javaclaw.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentTest {

    private static final ConversationKey WEB_CHAT = ConversationKey.web();

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec streamSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock ChatSessions chatSessions;
    @Mock SessionTitles sessionTitles;

    /** Events are a stream, so record and assert the sequence rather than mocking callbacks. */
    final List<AgentEvent> events = new ArrayList<>();

    DefaultAgent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agent = new DefaultAgent(chatClient, chatSessions, sessionTitles);
        lenient().when(chatSessions.active(WEB_CHAT, SessionKind.CHAT, SessionChannel.WEB)).thenReturn("session-1");
        lenient().when(chatClient.prompt("hello")).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
    }

    @Test
    void reportsEachTokenAndCompletionToListener() {
        when(streamSpec.content()).thenReturn(Flux.just("Hello ", "world"));

        agent.respondTo(AgentRequest.chat(WEB_CHAT, SessionChannel.WEB, "hello"), events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Token("world"),
                new AgentEvent.Done());
    }

    @Test
    void reportsErrorAndReturnsPartialResponseWhenStreamFails() {
        when(streamSpec.content()).thenReturn(Flux.concat(
                Flux.just("Hello "),
                Flux.error(new RuntimeException("boom"))));

        agent.respondTo(AgentRequest.chat(WEB_CHAT, SessionChannel.WEB, "hello"), events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("Hello "),
                new AgentEvent.Failed("boom"));
        verifyNoInteractions(sessionTitles);
    }

    @Test
    void fallsBackToBlockingCallWhenModelDoesNotSupportStreaming() {
        when(streamSpec.content()).thenReturn(Flux.error(new UnsupportedOperationException("streaming is not supported")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("full response");

        agent.respondTo(AgentRequest.chat(WEB_CHAT, SessionChannel.WEB, "hello"), events::add);

        assertThat(events).containsExactly(
                new AgentEvent.Token("full response"),
                new AgentEvent.Done());
    }

    @Test
    void titlesTheSessionAfterAChatExchange() {
        when(streamSpec.content()).thenReturn(Flux.just("Hi"));

        agent.respondTo(AgentRequest.chat(WEB_CHAT, SessionChannel.WEB, "hello"), events::add);

        verify(sessionTitles).afterExchange(WEB_CHAT, "session-1");
    }

    @Test
    void theRequestGoesToTheConversationsActiveSession() {
        when(streamSpec.content()).thenReturn(Flux.just("Hi"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisors = ArgumentCaptor.forClass(Consumer.class);

        agent.respondTo(AgentRequest.chat(WEB_CHAT, SessionChannel.WEB, "hello"), events::add);

        verify(requestSpec).advisors(advisors.capture());
        ChatClient.AdvisorSpec spec = mock(ChatClient.AdvisorSpec.class, RETURNS_SELF);
        advisors.getValue().accept(spec);
        verify(spec).param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-1");
    }

    @Test
    void aRequestThatNamesASessionOfItsConversationGoesThere() {
        when(streamSpec.content()).thenReturn(Flux.just("Hi"));
        when(chatSessions.isSessionOf(WEB_CHAT, "session-2")).thenReturn(true);
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisors = advisorCaptor();

        agent.respondTo(AgentRequest.chat(WEB_CHAT, "session-2", SessionChannel.WEB, "hello"), events::add);

        verify(requestSpec).advisors(advisors.capture());
        ChatClient.AdvisorSpec spec = mock(ChatClient.AdvisorSpec.class, RETURNS_SELF);
        advisors.getValue().accept(spec);
        verify(spec).param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-2");
        verify(sessionTitles).afterExchange(WEB_CHAT, "session-2");
    }

    @Test
    void aRequestThatNamesASessionOfAnotherConversationIsRefused() {
        when(chatSessions.isSessionOf(WEB_CHAT, "telegram-session")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        agent.respondTo(AgentRequest.chat(WEB_CHAT, "telegram-session", SessionChannel.WEB, "hello"), events::add))
                .isInstanceOf(IllegalArgumentException.class);
        verify(chatClient, org.mockito.Mockito.never()).prompt(org.mockito.ArgumentMatchers.anyString());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor() {
        return ArgumentCaptor.forClass(Consumer.class);
    }
}
