package ai.javaclaw.channels.discord;

public class DiscordUtils {
    private DiscordUtils() {}

    public static String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        String normalized = userId.trim();
        if (normalized.startsWith("<@") && normalized.endsWith(">")) {
            normalized = normalized.substring(2, normalized.length() - 1);
            if (normalized.startsWith("!")) {
                normalized = normalized.substring(1);
            }
        }
        return normalized.isBlank() ? null : normalized;
    }
}
