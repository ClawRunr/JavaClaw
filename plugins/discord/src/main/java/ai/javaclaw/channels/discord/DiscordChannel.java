package ai.javaclaw.channels.discord;

import ai.javaclaw.channels.ApprovalChat;
import ai.javaclaw.channels.ApprovalFormat;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelApprovals;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static java.util.Optional.ofNullable;
import static java.util.regex.Pattern.quote;

public class DiscordChannel extends ListenerAdapter implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);

    private final String allowedUserId;
    static final String ERROR_MESSAGE = "Sorry, something went wrong while handling your message.";

    private final ChannelApprovals approvals;
    private final ChannelRegistry channelRegistry;
    private volatile MessageChannel lastChannel;

    public DiscordChannel(String allowedUserId, ChannelApprovals approvals, ChannelRegistry channelRegistry) {
        this.allowedUserId = DiscordUserIds.normalize(allowedUserId);
        this.approvals = approvals;
        this.channelRegistry = channelRegistry;
        channelRegistry.registerChannel(this);
        log.info("Started Discord integration");
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!shouldHandle(event)) {
            return;
        }

        String userId = DiscordUserIds.normalize(event.getAuthor().getId());
        MessageChannel channel = event.getChannel();
        String content = normalizeText(event.getJDA(), event.getMessage(), event.isFromGuild());

        if (content == null) {
            return;
        }

        if (!isAllowedUser(userId)) {
            log.warn("Ignoring Discord message from unauthorized user '{}'", userId);
            reply(channel, "I'm sorry, I don't accept instructions from you.");
            return;
        }

        String conversationId = getConversationId(channel.getId());
        // /approve and /deny are answered here, on JDA's event thread, and never reach the model
        if (approvals.handleCommand(conversationId, content, text -> reply(channel, text))) {
            return;
        }
        lastChannel = channel;
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), content));
        // The turn runs in the background, so JDA can still deliver an /approve it may wait for
        // Approvals are asked only in a DM; in a server channel, calls that need one are refused
        DiscordChat chat = new DiscordChat(channel, event.isFromType(ChannelType.PRIVATE));
        approvals.respond(conversationId, content, chat, e -> {
            log.error("Failed to handle Discord message for conversation '{}'", conversationId, e);
            reply(channel, ERROR_MESSAGE);
        });
    }

    @Override
    public void sendMessage(String message) {
        MessageChannel channel = lastChannel;
        if (channel == null) {
            log.error("No known Discord channel, cannot send message '{}'", message);
            return;
        }
        reply(channel, message);
    }

    private boolean shouldHandle(MessageReceivedEvent event) {
        User author = event.getAuthor();
        if (author.isBot() || event.isWebhookMessage()) {
            return false;
        }
        return event.isFromType(ChannelType.PRIVATE)
                || event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
    }

    private boolean isAllowedUser(String userId) {
        return userId != null && userId.equalsIgnoreCase(allowedUserId);
    }

    /**
     * The channel a turn came from. Approval requests are sent in Discord markdown with mentions
     * switched off, so nothing a tool call contains can ping anyone, and are edited to show their
     * outcome.
     *
     * @param canAsk whether this is a DM, the only place the owner is asked
     */
    private record DiscordChat(MessageChannel channel, boolean canAsk) implements ApprovalChat {

        private static final EnumSet<Message.MentionType> NO_MENTIONS = EnumSet.noneOf(Message.MentionType.class);

        @Override
        public ApprovalFormat format() {
            return ApprovalFormat.DISCORD_MARKDOWN;
        }

        @Override
        public void send(String text) {
            reply(channel, text);
        }

        @Override
        public Object sendFormatted(String markdown) {
            Message sent = channel.sendMessage(markdown).setAllowedMentions(NO_MENTIONS).complete();
            return sent == null ? null : sent.getId();
        }

        @Override
        public boolean edit(Object messageId, String markdown) {
            if (!(messageId instanceof String id)) {
                return false;
            }
            try {
                channel.editMessageById(id, markdown).setAllowedMentions(NO_MENTIONS).complete();
                return true;
            } catch (RuntimeException e) {
                log.warn("Could not edit Discord message {}: {}", id, e.getMessage());
                return false;
            }
        }
    }

    private static void reply(MessageChannel channel, String text) {
        channel.sendMessage(text).queue();
    }

    private static String normalizeText(JDA jda, Message message, boolean guildMessage) {
        String content = message.getContentRaw();
        if (content == null) {
            return null;
        }
        if (guildMessage) {
            String mention = ofNullable(jda.getSelfUser()).map(User::getAsMention).orElse("");
            content = content.replaceFirst("^\\s*" + quote(mention) + "\\s*", "");
        }
        content = content.trim();
        return content.isBlank() ? null : content;
    }

    private static String getConversationId(String channelId) {
        return "discord-" + channelId;
    }
}
