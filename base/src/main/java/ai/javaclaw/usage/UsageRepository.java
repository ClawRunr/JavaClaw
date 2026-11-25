package ai.javaclaw.usage;

/**
 * Persists {@link UsageRecord}s so they survive application restarts.
 */
public interface UsageRepository {

    void save(UsageRecord record);
}
