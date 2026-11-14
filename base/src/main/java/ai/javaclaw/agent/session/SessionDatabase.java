package ai.javaclaw.agent.session;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The H2 database that holds the sessions, in its own files under {@code {workspace}/data/}.
 *
 * <p>Deliberately not a {@link DataSource} bean: Spring Boot backs off its own DataSource as soon
 * as one exists, and JobRunr would then write into this database. The pool is handed only to the
 * session repository.
 */
public final class SessionDatabase implements AutoCloseable {

    /** Spring AI's H2 schema; every statement is {@code IF NOT EXISTS}, so it runs on every start. */
    static final String SCHEMA = "org/springframework/ai/session/jdbc/schema-h2.sql";

    private final JdbcConnectionPool pool;

    /**
     * Opens (and on first use creates) the database {@code {dataDirectory}/sessions}.
     *
     * @param dataDirectory the workspace's data directory, created if missing
     */
    SessionDatabase(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the sessions directory " + dataDirectory, e);
        }
        Path file = dataDirectory.resolve("sessions").toAbsolutePath();
        this.pool = JdbcConnectionPool.create("jdbc:h2:file:" + file, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(pool);
    }

    /** The pool, for the session repository only. */
    DataSource dataSource() {
        return pool;
    }

    /** Closes every connection, which releases H2's lock on the file before a context restart. */
    @Override
    public void close() {
        pool.dispose();
    }
}
