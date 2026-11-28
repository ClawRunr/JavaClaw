package ai.javaclaw.usage;

/**
 * Where {@link UsageRecord}s are kept.
 */
public interface UsageRepository {

    void save(UsageRecord record);
}
