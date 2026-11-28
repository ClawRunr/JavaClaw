package ai.javaclaw.usage;

import ai.javaclaw.agent.session.SessionDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * Keeps one row per model call in the {@code token_usage} table of the sessions database.
 */
@Component
public class JdbcUsageRepository implements UsageRepository {

    /** Every statement is {@code IF NOT EXISTS}, so it runs on every start. */
    static final String SCHEMA = "ai/javaclaw/usage/schema-h2.sql";

    private final JdbcClient jdbc;

    @Autowired
    public JdbcUsageRepository(SessionDatabase sessionDatabase) {
        this(sessionDatabase.dataSource());
    }

    JdbcUsageRepository(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void save(UsageRecord record) {
        jdbc.sql("""
                        INSERT INTO token_usage (recorded_at, session_id, conversation, channel, kind, model,
                                                 input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                                 total_tokens)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(Timestamp.from(record.recordedAt()), record.sessionId(), record.conversation(),
                        record.channel(), record.kind(), record.model(), record.inputTokens(),
                        record.outputTokens(), record.cacheReadTokens(), record.cacheWriteTokens(),
                        record.totalTokens())
                .update();
    }
}
