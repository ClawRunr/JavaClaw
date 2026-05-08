package ai.javaclaw.channels.discord;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiscordChannelTest {

    private final Agent agent = mock(Agent.class);

    @Test
    void ignoresBotMessages() {
        DiscordChannel channel = channel("123");

        channel.onMessageReceived(event(true, false, false, "123", "C1", "hello"));

        verifyNoInteractions(agent);
    }

    @Test
    void ignoresGuildMessagesWithoutMention() {
        DiscordChannel channel = channel("123");

        channel.onMessageReceived(event(false, false, false, "123", "C1", "hello"));

        verifyNoInteractions(agent);
    }

    @Test
    void rejectsUnauthorizedUsers() {
        DiscordChannel channel = channel("123");
        MessageChannelUnion channelMock = messageChannel("C1");

        channel.onMessageReceived(event(false, false, true, "999", "C1", "<@111> hello", channelMock));

        verify(agent, never()).respondTo(anyString(), anyString());
        verify(channelMock).sendMessage("I'm sorry, I don't accept instructions from you.");
    }

    @Test
    void processesDirectMessages() {
        DiscordChannel channel = channel("123");
        MessageChannelUnion channelMock = messageChannel("D1");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.onMessageReceived(event(false, true, false, "123", "D1", "hello", channelMock));

        verify(agent).respondTo(eq("discord-D1"), eq("hello"));
        verify(channelMock).sendMessage("hi");
    }

    @Test
    void stripsMentionInGuildMessages() {
        DiscordChannel channel = channel("123");
        MessageChannelUnion channelMock = messageChannel("C1");
        when(agent.respondTo(anyString(), anyString())).thenReturn("hi");

        channel.onMessageReceived(event(false, false, true, "123", "C1", "<@111> hello there", channelMock));

        verify(agent).respondTo(eq("discord-C1"), eq("hello there"));
    }

    @Test
    void sendMessageUsesLastKnownChannel() {
        DiscordChannel channel = channel("123");
        MessageChannelUnion channelMock = messageChannel("D1");
        when(agent.respondTo(anyString(), anyString())).thenReturn("ok");
        channel.onMessageReceived(event(false, true, false, "123", "D1", "hello", channelMock));

        channel.sendMessage("background update");

        verify(channelMock).sendMessage("background update");
    }

    @Test
    void sendMessageDoesNothingWithoutKnownChannel() {
        DiscordChannel channel = channel("123");

        channel.sendMessage("hello");

        verifyNoInteractions(agent);
    }

    private DiscordChannel channel(String allowedUser) {
        return new DiscordChannel(allowedUser, agent, new ChannelRegistry());
    }

    private MessageReceivedEvent event(boolean authorIsBot,
                                       boolean directMessage,
                                       boolean mentioned,
                                       String authorId,
                                       String channelId,
                                       String content) {
        return event(authorIsBot, directMessage, mentioned, authorId, channelId, content, messageChannel(channelId));
    }

    private MessageReceivedEvent event(boolean authorIsBot,
                                       boolean directMessage,
                                       boolean mentioned,
                                       String authorId,
                                       String channelId,
                                       String content,
                                       MessageChannelUnion channelUnion) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        User author = mock(User.class);
        Message message = mock(Message.class);
        Mentions mentions = mock(Mentions.class);
        JDA jda = mock(JDA.class);
        SelfUser selfUser = mock(SelfUser.class);

        when(event.getAuthor()).thenReturn(author);
        when(author.isBot()).thenReturn(authorIsBot);
        when(author.getId()).thenReturn(authorId);
        when(event.isWebhookMessage()).thenReturn(false);
        when(event.isFromType(ChannelType.PRIVATE)).thenReturn(directMessage);
        when(event.isFromGuild()).thenReturn(!directMessage);
        when(event.getMessage()).thenReturn(message);
        when(message.getContentRaw()).thenReturn(content);
        when(message.getMentions()).thenReturn(mentions);
        when(mentions.isMentioned(selfUser)).thenReturn(mentioned);
        when(event.getJDA()).thenReturn(jda);
        when(jda.getSelfUser()).thenReturn(selfUser);
        when(selfUser.getAsMention()).thenReturn("<@111>");
        when(event.getChannel()).thenReturn(channelUnion);
        when(channelUnion.getId()).thenReturn(channelId);
        return event;
    }

    private MessageChannelUnion messageChannel(String channelId) {
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(channel.getId()).thenReturn(channelId);
        when(channel.sendMessage(anyString())).thenReturn(action);
        return channel;
    }
}
