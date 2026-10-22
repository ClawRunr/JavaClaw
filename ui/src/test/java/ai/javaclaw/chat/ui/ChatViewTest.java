package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ChatView} server-side, without a browser: the view is built from a mocked {@link
 * Agent} and {@link ChatMemoryRepository}, and the transcript is read back from the component tree,
 * so each test sees what a reader would see rather than the view's internals.
 */
class ChatViewTest {

    /** JUnit creates a fresh instance per test, so the mocks below never leak between tests. */
    ChatViewTest() {
    }

    /** The agent the view talks to; each test stubs only what it needs. */
    private final Agent agent = mock(Agent.class);

    /** The agent's own conversation store, which is the view's only source of history. */
    private final ChatMemoryRepository memory = mock(ChatMemoryRepository.class);

    /**
     * History comes from the {@code web} conversation, the one the HTMX chat also uses, and only
     * user and assistant turns are shown. Reading another conversation, or showing the system
     * prompt, would fail here.
     */
    @Test
    void showsTheWebConversationsUserAndAssistantMessagesInOrder() {
        when(memory.findByConversationId("web")).thenReturn(List.of(
                new SystemMessage("system prompt"),
                new UserMessage("first question"),
                new AssistantMessage("first answer"),
                new UserMessage("second question")));

        ChatView view = new ChatView(agent, memory);

        assertThat(transcript(view)).containsExactly(
                "user: first question",
                "assistant: first answer",
                "user: second question");
    }

    /** A conversation that has not started yet is a normal state, not an error. */
    @Test
    void anEmptyConversationShowsAnEmptyTranscript() {
        when(memory.findByConversationId("web")).thenReturn(List.of());

        assertThat(transcript(new ChatView(agent, memory))).isEmpty();
    }

    /**
     * The whole send path: one blocking call to the agent on the {@code web} conversation, the
     * question and the reply appended in order, the composer cleared and the button usable again.
     */
    @Test
    void sendingShowsTheQuestionAndTheAgentsBlockingReply() {
        when(memory.findByConversationId("web")).thenReturn(List.of());
        when(agent.respondTo("web", "hello")).thenReturn("hi there");
        ChatView view = new ChatView(agent, memory);

        composer(view).setValue("hello");
        send(view).click();

        verify(agent).respondTo("web", "hello");
        assertThat(transcript(view)).containsExactly("user: hello", "assistant: hi there");
        assertThat(composer(view).getValue()).isEmpty();
        assertThat(send(view).isEnabled()).isTrue();
    }

    /** Whitespace is not a question: nothing reaches the agent and nothing is added. */
    @Test
    void blankInputIsNotSent() {
        when(memory.findByConversationId("web")).thenReturn(List.of());
        ChatView view = new ChatView(agent, memory);

        composer(view).setValue("   \n ");
        send(view).click();

        verify(agent, never()).respondTo(anyString(), anyString());
        assertThat(transcript(view)).isEmpty();
    }

    /**
     * A provider error can carry details such as a rejected API key. The reader gets a generic line
     * instead, and the page stays usable; the details belong in the server log only.
     */
    @Test
    void anAgentFailureShowsAGenericErrorAndLeavesTheComposerUsable() {
        when(memory.findByConversationId("web")).thenReturn(List.of());
        when(agent.respondTo("web", "hello")).thenThrow(new IllegalStateException("api key sk-secret rejected"));
        ChatView view = new ChatView(agent, memory);

        composer(view).setValue("hello");
        send(view).click();

        List<String> lines = transcript(view);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("user: hello");
        assertThat(lines.get(1)).startsWith("error: ").doesNotContain("sk-secret");
        assertThat(send(view).isEnabled()).isTrue();
        assertThat(composer(view).isEnabled()).isTrue();
    }

    /** {@link Agent#respondTo(String, String)} may return null; that must not break the page. */
    @Test
    void aNullReplyIsShownAsAnEmptyAnswerRatherThanFailing() {
        when(memory.findByConversationId("web")).thenReturn(List.of());
        when(agent.respondTo("web", "hello")).thenReturn(null);
        ChatView view = new ChatView(agent, memory);

        composer(view).setValue("hello");
        send(view).click();

        assertThat(transcript(view)).containsExactly("user: hello", "assistant: ");
    }

    /**
     * A damaged conversation file must not take the whole page down, and its cause (which can
     * include file paths) is not shown; the composer stays usable.
     */
    @Test
    void anUnreadableConversationShowsAnErrorInsteadOfBreakingThePage() {
        when(memory.findByConversationId("web")).thenThrow(new IllegalStateException("corrupt chat-web.yaml at /secret/path"));

        ChatView view = new ChatView(agent, memory);

        List<String> lines = transcript(view);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("error: ").doesNotContain("/secret/path");
        assertThat(send(view).isEnabled()).isTrue();
    }

    /**
     * Message text is untrusted: an agent can repeat anything it was given. It must be shown as
     * text, and no element may receive it as {@code innerHTML}, which is how it would execute.
     */
    @Test
    void markupInAMessageIsShownAsText() {
        when(memory.findByConversationId("web")).thenReturn(List.of(new AssistantMessage("<img src=x onerror=alert(1)>")));

        ChatView view = new ChatView(agent, memory);

        assertThat(transcript(view)).containsExactly("assistant: <img src=x onerror=alert(1)>");
        assertThat(descendants(view)
                .map(c -> c.getElement().getProperty("innerHTML"))
                .filter(html -> html != null))
                .isEmpty();
    }

    /**
     * Reads the transcript back as it is rendered.
     *
     * @param view the view under test
     * @return one {@code "role: text"} line per message, oldest first; empty when there are none
     */
    private static List<String> transcript(ChatView view) {
        Component transcript = byId(view, "transcript");
        return transcript.getChildren()
                .map(message -> role(message) + ": " + ((HasText) byClass(message, "message-text")).getText())
                .toList();
    }

    /**
     * Reads a message's role from its {@code message-<role>} class name.
     *
     * @param message one child of the transcript
     * @return {@code user}, {@code assistant} or {@code error}
     * @throws AssertionError when the message carries no role class
     */
    private static String role(Component message) {
        return message.getElement().getClassList().stream()
                .filter(name -> name.startsWith("message-") && !name.equals("message-text"))
                .map(name -> name.substring("message-".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("message without a role class"));
    }

    /**
     * Finds the composer.
     *
     * @param view the view under test
     * @return the composer text area
     */
    private static TextArea composer(ChatView view) {
        return (TextArea) byId(view, "composer");
    }

    /**
     * Finds the send button.
     *
     * @param view the view under test
     * @return the send button
     */
    private static Button send(ChatView view) {
        return (Button) byId(view, "send");
    }

    /**
     * Finds a component by id anywhere below {@code root}.
     *
     * @param root where to search
     * @param id   the component id
     * @return the first match
     * @throws AssertionError when nothing has that id, so a renamed id fails loudly
     */
    private static Component byId(Component root, String id) {
        return descendants(root)
                .filter(c -> c.getId().equals(Optional.of(id)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component with id " + id));
    }

    /**
     * Finds a component by class name anywhere below {@code root}.
     *
     * @param root      where to search
     * @param className the class name
     * @return the first match
     * @throws AssertionError when nothing carries that class name
     */
    private static Component byClass(Component root, String className) {
        return descendants(root)
                .filter(c -> c.getElement().getClassList().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component with class " + className));
    }

    /**
     * Walks the component tree below {@code root}.
     *
     * @param root where to start
     * @return every descendant, depth first, excluding {@code root} itself
     */
    private static Stream<Component> descendants(Component root) {
        return root.getChildren().flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
