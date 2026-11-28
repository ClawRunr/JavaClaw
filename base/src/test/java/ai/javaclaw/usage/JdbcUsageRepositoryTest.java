package ai.javaclaw.usage;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUsageRepositoryTest {

    JdbcConnectionPool pool;
    JdbcUsageRepository repository;

    @BeforeEach
    void setUp() {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        repository = new JdbcUsageRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.dispose();
    }

    @Test
    void savesOneRowPerRecord() {
        Instant recordedAt = Instant.parse("2026-11-24T15:30:12Z");
        repository.save(new UsageRecord(recordedAt, "20261124_153012_a1b2c3d4", "telegram:42", "telegram", "chat",
                "claude-sonnet-4-6", 1200, 350, 800, 100, 1550));

        Map<String, Object> row = JdbcClient.create(pool).sql("SELECT * FROM token_usage").query().singleRow();

        assertThat(((Timestamp) row.get("RECORDED_AT")).toInstant()).isEqualTo(recordedAt);
        assertThat(row).containsEntry("SESSION_ID", "20261124_153012_a1b2c3d4")
                .containsEntry("CONVERSATION", "telegram:42")
                .containsEntry("CHANNEL", "telegram")
                .containsEntry("KIND", "chat")
                .containsEntry("MODEL", "claude-sonnet-4-6")
                .containsEntry("INPUT_TOKENS", 1200)
                .containsEntry("OUTPUT_TOKENS", 350)
                .containsEntry("CACHE_READ_TOKENS", 800)
                .containsEntry("CACHE_WRITE_TOKENS", 100)
                .containsEntry("TOTAL_TOKENS", 1550);
    }

    @Test
    void storesCallsWithoutSessionWithEmptySessionColumns() {
        repository.save(new UsageRecord(Instant.now(), null, null, null, null, null, 10, 5, 0, 0, 15));

        Map<String, Object> row = JdbcClient.create(pool).sql("SELECT * FROM token_usage").query().singleRow();

        assertThat(row.get("SESSION_ID")).isNull();
        assertThat(row.get("CONVERSATION")).isNull();
        assertThat(row.get("CHANNEL")).isNull();
        assertThat(row.get("KIND")).isNull();
        assertThat(row.get("MODEL")).isNull();
    }

    @Test
    void schemaCanRunAgainOnAnExistingDatabase() {
        repository.save(new UsageRecord(Instant.now(), null, null, null, null, null, 1, 1, 0, 0, 2));

        new JdbcUsageRepository(pool);

        Long rows = JdbcClient.create(pool).sql("SELECT COUNT(*) FROM token_usage").query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }
}
