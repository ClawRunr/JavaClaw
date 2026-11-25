package ai.javaclaw.agent;

import ai.javaclaw.agent.session.SessionStoreConfiguration;
import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The session store as the application builds it — Spring AI's JDBC repository with JavaClaw's
 * indexed attributes — on an in-memory H2 database of its own, and {@link ChatSessions} on top.
 */
final class TestSessionStore {

    final JdbcSessionRepository repository;
    final SessionService service;
    final TickingClock clock = new TickingClock();
    final ChatSessions chatSessions;

    TestSessionStore() {
        JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:sessions-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/ai/session/jdbc/schema-h2.sql")).execute(pool);
        repository = JdbcSessionRepository.builder()
                .dataSource(pool)
                .indexedAttributes(SessionStoreConfiguration.INDEXED_ATTRIBUTES)
                .build();
        service = DefaultSessionService.builder().sessionRepository(repository).build();
        chatSessions = new ChatSessions(service, repository, clock);
    }

    /**
     * A clock at least one second later on every reading, so every change happens at its own time.
     * Spring AI stamps sessions and events with the system clock, so it never falls behind that.
     */
    static final class TickingClock extends Clock {

        private Instant last = Instant.EPOCH;

        @Override
        public synchronized Instant instant() {
            Instant now = Instant.now();
            last = now.isAfter(last.plus(Duration.ofSeconds(1))) ? now : last.plus(Duration.ofSeconds(1));
            return last;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
