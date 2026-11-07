package ai.javaclaw.channels.discord;

/** Discord user ids as people type them: a raw id, or a mention such as {@code <@123>} or {@code <@!123>}. */
final class DiscordUserIds {

    private DiscordUserIds() {
    }

    /** @return the bare user id, or {@code null} when there is none */
    static String normalize(String userId) {
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
