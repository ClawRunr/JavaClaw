package ai.javaclaw.chat;

import ai.javaclaw.agent.ChatCommands;
import ai.javaclaw.agent.ChatHistory;
import ai.javaclaw.agent.ChatSessions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.context.annotation.Bean;

/**
 * The chat's sessions as the application wires them, on Spring AI's in-memory session store, for
 * tests that run the web chat without the whole application. Imported with {@code @Import}; not a
 * {@code @Configuration}, so the application's component scan never finds it.
 */
public class InMemoryChatSessions {

    @Bean
    InMemorySessionRepository sessionRepository() {
        return InMemorySessionRepository.builder().build();
    }

    @Bean
    SessionService sessionService(InMemorySessionRepository sessionRepository) {
        return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
    }

    @Bean
    ChatSessions chatSessions(SessionService sessionService, InMemorySessionRepository sessionRepository) {
        return new ChatSessions(sessionService, sessionRepository);
    }

    @Bean
    ChatHistory chatHistory(SessionService sessionService) {
        return new ChatHistory(sessionService);
    }

    @Bean
    ChatCommands chatCommands(ChatSessions chatSessions) {
        return new ChatCommands(chatSessions);
    }
}
