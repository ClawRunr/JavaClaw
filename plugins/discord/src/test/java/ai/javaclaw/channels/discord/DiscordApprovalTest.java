package ai.javaclaw.channels.discord;

import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.testsupport.ChannelApprovalFixture;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a Discord turn is approved in the same channel: the request arrives as a
 * message, {@code /approve <id>} runs the tool, and the answer follows. Real agent, real gate;
 * only JDA is faked.
 */
class DiscordApprovalTest {

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final MessageChannelUnion dm = messageChannel();
    final DiscordChannel channel = new DiscordChannel("123", fixture.approvals(), new ChannelRegistry());

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedInTheChannelAndTheAnswerFollows() {
        channel.onMessageReceived(event("123", "fetch example.com"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String request = sent.getFirst();
        assertThat(request).contains(ChannelApprovalFixture.TOOL).contains(ARGUMENTS);
        assertThat(fixture.toolRuns()).isEmpty();

        channel.onMessageReceived(event("123", "/approve " + shortId(request)));

        await(() -> sent.contains(ANSWER));
        assertThat(sent).anyMatch(m -> m.startsWith("Approved " + shortId(request)));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void anotherDiscordUserCannotApprove() throws Exception {
        channel.onMessageReceived(event("123", "fetch example.com"));
        await(() -> sent.stream().anyMatch(m -> m.contains("wants to run")));
        String id = shortId(sent.getFirst());

        channel.onMessageReceived(event("999", "/approve " + id));

        Thread.sleep(300);
        assertThat(sent).contains("I'm sorry, I don't accept instructions from you.");
        assertThat(sent).noneMatch(m -> m.startsWith("Approved"));
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("discord-D1")).isNotEmpty();

        channel.onMessageReceived(event("123", "/deny " + id));
        await(() -> sent.stream().anyMatch(m -> m.startsWith("Denied " + id)));
        assertThat(fixture.toolRuns()).isEmpty();
    }

    private MessageReceivedEvent event(String authorId, String content) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        User author = mock(User.class);
        Message message = mock(Message.class);
        Mentions mentions = mock(Mentions.class);
        JDA jda = mock(JDA.class);
        SelfUser self = mock(SelfUser.class);
        when(event.getAuthor()).thenReturn(author);
        when(author.getId()).thenReturn(authorId);
        when(event.isFromType(ChannelType.PRIVATE)).thenReturn(true);
        when(event.isFromGuild()).thenReturn(false);
        when(event.getMessage()).thenReturn(message);
        when(message.getContentRaw()).thenReturn(content);
        when(message.getMentions()).thenReturn(mentions);
        when(event.getJDA()).thenReturn(jda);
        when(jda.getSelfUser()).thenReturn(self);
        when(event.getChannel()).thenReturn(dm);
        return event;
    }

    private MessageChannelUnion messageChannel() {
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("D1");
        when(channel.sendMessage(anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0, String.class));
            return mock(MessageCreateAction.class);
        });
        return channel;
    }
}
