package ai.javaclaw.usage;

/**
 * Origin of a model call, inferred from the conversation id conventions used by channels and tasks.
 */
public enum UsageSource {

    WEB_CHAT("web_chat"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    WHATSAPP("whatsapp"),
    SCHEDULED_TASK("scheduled_task"),
    UNKNOWN("unknown");

    private final String wireName;

    UsageSource(String wireName) {
        this.wireName = wireName;
    }

    /** Stable string stored in usage YAML files. */
    public String wireName() {
        return wireName;
    }

    public static UsageSource fromWireName(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return UNKNOWN;
        }
        for (UsageSource source : values()) {
            if (source.wireName.equals(wireName)) {
                return source;
            }
        }
        return UNKNOWN;
    }

    /**
     * Resolves the source from a conversation id.
     */
    public static UsageSource fromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UNKNOWN;
        }
        if (conversationId.startsWith("task-")) {
            return SCHEDULED_TASK;
        }
        if (conversationId.startsWith("telegram-")) {
            return TELEGRAM;
        }
        if (conversationId.startsWith("discord-")) {
            return DISCORD;
        }
        if ("web".equals(conversationId) || conversationId.startsWith("web-") || conversationId.endsWith("/web")) {
            return WEB_CHAT;
        }
        if (looksLikeWhatsAppJid(conversationId)) {
            return WHATSAPP;
        }
        return UNKNOWN;
    }

    /**
     * When the source is a scheduled task, the conversation id is the durable task key used for chat memory.
     */
    public static String taskIdFor(String conversationId) {
        return fromConversationId(conversationId) == SCHEDULED_TASK ? conversationId : null;
    }

    private static boolean looksLikeWhatsAppJid(String conversationId) {
        return conversationId.contains("@s.whatsapp.net")
                || conversationId.contains("@g.us")
                || conversationId.contains("@lid")
                || conversationId.startsWith("whatsapp-");
    }
}
