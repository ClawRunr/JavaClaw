package ai.javaclaw.channels;

/**
 * How a messaging channel marks up an approval message, and how it escapes text it did not write.
 *
 * <p>Everything a tool call supplies (paths, arguments, previews, even the tool's name, which the
 * model chooses) goes through {@link #text}, {@link #code} or {@link #block}, so it is shown as
 * written and never as the channel's markup.
 */
public enum ApprovalFormat {

    /** Telegram's HTML parse mode. */
    TELEGRAM_HTML {
        @Override
        public String text(String text) {
            return html(text);
        }

        @Override
        public String bold(String text) {
            return "<b>" + html(text) + "</b>";
        }

        @Override
        public String code(String text) {
            return "<code>" + html(text) + "</code>";
        }

        @Override
        public String block(String text) {
            return "<pre>" + html(text) + "</pre>";
        }
    },

    /** Discord markdown; mentions are switched off where the message is sent. */
    DISCORD_MARKDOWN {
        @Override
        public String text(String text) {
            return backslashEscaped(text);
        }

        @Override
        public String bold(String text) {
            return "**" + backslashEscaped(text) + "**";
        }

        @Override
        public String code(String text) {
            return "`` " + brokenBackticks(text) + " ``";
        }

        @Override
        public String block(String text) {
            return "```\n" + brokenBackticks(text) + "\n```";
        }
    },

    /**
     * WhatsApp's own markup. It has no escape character, so tool text is only ever shown in
     * monospace, where WhatsApp applies no other formatting; plain text gets zero-width spaces after
     * its formatting characters as a best effort.
     */
    WHATSAPP {
        @Override
        public String text(String text) {
            return text.replaceAll("([*_~`])", "$1​");
        }

        @Override
        public String bold(String text) {
            return "*" + text(text) + "*";
        }

        @Override
        public String code(String text) {
            return "```" + brokenBackticks(text) + "```";
        }

        @Override
        public String block(String text) {
            return "```" + brokenBackticks(text) + "```";
        }

        @Override
        public String command(String command) {
            // An underscore inside a word starts no italics, and the command must stay exactly as typed
            return command;
        }
    },

    /** Teams' markdown, which also renders some HTML, so angle brackets are escaped as entities. */
    TEAMS_MARKDOWN {
        @Override
        public String text(String text) {
            return backslashEscaped(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .replace("\n", "  \n");
        }

        @Override
        public String bold(String text) {
            return "**" + text(text) + "**";
        }

        @Override
        public String code(String text) {
            return "`` " + brokenBackticks(text) + " ``";
        }

        @Override
        public String block(String text) {
            return "\n```\n" + brokenBackticks(text) + "\n```\n";
        }

        @Override
        public String lineBreak() {
            // Markdown joins single lines into one paragraph
            return "  \n";
        }
    };

    /** Plain text, escaped so none of it is read as markup. */
    public abstract String text(String text);

    public abstract String bold(String text);

    /** Inline monospace, for a path or a short value. */
    public abstract String code(String text);

    /** A monospace block on its own lines, for previews and argument lists. */
    public abstract String block(String text);

    /**
     * A command the owner can send back, such as {@code /approve_a1b2c3}: JavaClaw's own ASCII text,
     * shown so that it reads (and, on Telegram, taps) exactly as it must be sent.
     */
    public String command(String command) {
        return text(command);
    }

    /** What separates two lines of the message. */
    public String lineBreak() {
        return "\n";
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * A backslash before a character that can start markup shows it literally, in Discord and in
     * CommonMark: emphasis, code, quotes, headings, lists, links, spoilers and Discord's
     * {@code <@mention>} / {@code <#channel>} syntax.
     */
    private static String backslashEscaped(String text) {
        return text.replaceAll("([\\\\`*_~|>#\\[\\]()\\-+!<])", "\\\\$1");
    }

    /**
     * Code spans and blocks cannot be escaped, only kept from closing early: a zero-width space after
     * every backtick leaves no run of them that could end the span.
     */
    private static String brokenBackticks(String text) {
        return text.replace("`", "`​");
    }
}
