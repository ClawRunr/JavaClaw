package ai.javaclaw.agent;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commands a person can type in any chat, handled before the message reaches the model. A
 * command is never sent to the model and never stored.
 * <ul>
 *     <li>{@code /new [name]}: starts a new session in the conversation, titled {@code name} if
 *     one is given</li>
 * </ul>
 */
@Component
public class ChatCommands {

    /** The reply to {@code /new}. */
    public static final String NEW_CHAT_REPLY = "Started a new conversation.";

    /** {@code /new}, optionally addressed to a bot ({@code /new@bot}), optionally followed by a name. */
    private static final Pattern NEW_CHAT = Pattern.compile("/new(?:@(\\S+))?(?:\\s+(.*))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ChatSessions chatSessions;

    public ChatCommands(ChatSessions chatSessions) {
        this.chatSessions = chatSessions;
    }

    /**
     * Runs {@code text} if it is a command.
     *
     * @return the reply to send back, empty if {@code text} is not a command and goes to the model
     */
    public Optional<String> handle(ConversationKey key, SessionChannel channel, String text) {
        return handle(key, channel, text, () -> null);
    }

    /**
     * Runs {@code text} if it is a command, also when it is addressed to this bot as
     * {@code /command@botName} (Telegram).
     *
     * @param botName this bot's user name, looked up only for a command addressed to a bot; a
     *                command addressed to another bot is not one
     * @return the reply to send back, empty if {@code text} is not a command and goes to the model
     */
    public Optional<String> handle(ConversationKey key, SessionChannel channel, String text, Supplier<String> botName) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher command = NEW_CHAT.matcher(text.strip());
        if (!command.matches() || !isForThisBot(command.group(1), botName)) {
            return Optional.empty();
        }
        chatSessions.create(key, SessionKind.CHAT, channel, command.group(2));
        return Optional.of(NEW_CHAT_REPLY);
    }

    private static boolean isForThisBot(String addressee, Supplier<String> botName) {
        if (addressee == null) {
            return true;
        }
        String name = botName.get();
        return name != null && addressee.equalsIgnoreCase(name.startsWith("@") ? name.substring(1) : name);
    }
}
