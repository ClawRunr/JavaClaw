package ai.javaclaw.agent.session;

import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Set;

/**
 * Session memory on Spring AI's JDBC session repository, in an H2 database of its own at
 * {@code {agent.workspace}/data/sessions}. Spring AI's auto-configuration builds the
 * {@code SessionService} on it.
 */
@Configuration(proxyBeanMethods = false)
public class SessionStoreConfiguration {

    @Bean(destroyMethod = "close")
    SessionDatabase sessionDatabase(@Value("${agent.workspace}") Resource workspace) throws IOException {
        return new SessionDatabase(workspace.getFilePath().resolve("data"));
    }

    @Bean
    JdbcSessionRepository sessionRepository(SessionDatabase sessionDatabase) {
        return JdbcSessionRepository.builder()
                .dataSource(sessionDatabase.dataSource())
                // SessionService cannot list all sessions; the chat lists them by this attribute
                .indexedAttributes(Set.of(SessionAttributes.AGENT_NAME))
                .build();
    }
}
