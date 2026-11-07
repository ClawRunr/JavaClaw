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
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.javaclaw.testsupport.ChannelApprovalFixture.ANSWER;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.ARGUMENTS;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.await;
import static ai.javaclaw.testsupport.ChannelApprovalFixture.shortId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tool call from a Discord turn is approved in the same channel: the request arrives in Discord
 * markdown with mentions off, {@code /approve_<id>} runs the tool, the request is edited to show the
 * outcome, and the answer follows. Real agent, real gate; only JDA is faked.
 */
class DiscordApprovalTest {

    final ChannelApprovalFixture fixture = new ChannelApprovalFixture();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final Map<String, String> edits = new ConcurrentHashMap<>();
    final List<Collection<Message.MentionType>> allowedMentions = new CopyOnWriteArrayList<>();
    final AtomicInteger ids = new AtomicInteger();
    final MessageChannelUnion dm = messageChannel();
    final DiscordChannel channel = new DiscordChannel("123", fixture.approvals(), new ChannelRegistry());

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aToolCallIsApprovedTheRequestIsEditedAndTheAnswerFollows() {
        channel.onMessageReceived(event("123", "fetch example.com"));
        await(() -> !sent.isEmpty());
        String request = sent.getFirst();
        String id = shortId(request);
        assertThat(request).startsWith("**Approval needed: run " + ChannelApprovalFixture.TOOL + "**")
                .contains("```\n  url: https://example.com\n```");
        assertThat(allowedMentions).singleElement().satisfies(mentions -> assertThat(mentions).isEmpty());

        channel.onMessageReceived(event("123", "/approve_" + id));

        await(() -> sent.contains(ANSWER));
        assertThat(fixture.toolRuns()).containsExactly(ARGUMENTS);
        assertThat(edits.values()).singleElement().asString().contains("✅ **Approved**").doesNotContain("approve\\_");
        assertThat(sent).hasSize(2);
        assertThat(fixture.modelSaw()).noneMatch(text -> text.contains("/approve"));
    }

    @Test
    void anotherDiscordUserCannotApprove() throws Exception {
        channel.onMessageReceived(event("123", "fetch example.com"));
        await(() -> !sent.isEmpty());
        String id = shortId(sent.getFirst());

        channel.onMessageReceived(event("999", "/approve_" + id));

        Thread.sleep(300);
        assertThat(sent).contains("I'm sorry, I don't accept instructions from you.");
        assertThat(edits).isEmpty();
        assertThat(fixture.toolRuns()).isEmpty();
        assertThat(fixture.pending().pendingFor("discord-D1")).isNotEmpty();

        channel.onMessageReceived(event("123", "/deny " + id));
        await(() -> !edits.isEmpty());
        assertThat(edits.values()).singleElement().asString().contains("❌ **Denied**");
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

    @SuppressWarnings("unchecked")
    private MessageChannelUnion messageChannel() {
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("D1");
        when(channel.sendMessage(anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0, String.class));
            MessageCreateAction action = mock(MessageCreateAction.class);
            when(action.setAllowedMentions(any())).thenAnswer(mentions -> {
                allowedMentions.add(mentions.getArgument(0, Collection.class));
                return action;
            });
            Message created = mock(Message.class);
            when(created.getId()).thenReturn("m" + ids.incrementAndGet());
            when(action.complete()).thenReturn(created);
            return action;
        });
        when(channel.editMessageById(anyString(), anyString())).thenAnswer(invocation -> {
            edits.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            MessageEditAction action = mock(MessageEditAction.class);
            when(action.setAllowedMentions(any())).thenReturn(action);
            return action;
        });
        return channel;
    }
}
