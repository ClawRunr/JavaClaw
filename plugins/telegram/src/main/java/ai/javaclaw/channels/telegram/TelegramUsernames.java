package ai.javaclaw.channels.telegram;

/** Telegram usernames as people type them, with or without the leading {@code @}. */
final class TelegramUsernames {

    private TelegramUsernames() {
    }

    /** @return the username without {@code @} or surrounding spaces, or {@code null} when there is none */
    static String normalize(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }
}
