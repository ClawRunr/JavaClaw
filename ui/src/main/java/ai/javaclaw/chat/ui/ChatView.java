package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The Vaadin chat, served at {@code /ui} next to the HTMX chat at {@code /chat}.
 *
 * <p>It shows and continues the same {@code web} conversation the HTMX chat uses, read from the
 * agent's own {@link ChatMemoryRepository}: there is one history, owned by the agent, and this
 * view keeps no copy of it.
 *
 * <p>The reply is blocking: {@link Agent#respondTo(String, String)} runs inside the Vaadin request,
 * which holds the browser session's lock for the whole call. Until the answer is back, every
 * {@code /ui} tab of that browser session waits, and the question itself only appears together
 * with its reply. Streaming, which moves the call off the request thread and pushes the answer as
 * it arrives, is the next step and deliberately not part of this view yet.
 *
 * <p>Messages are rendered as plain text; markup in them is shown, never interpreted.
 */
@Route("")
@PageTitle("JavaClaw")
public class ChatView extends VerticalLayout {

    /** The conversation the HTMX chat uses as well, so both pages continue the same history. */
    static final String CONVERSATION_ID = "web";

    /**
     * Shown instead of the agent's exception, which can carry provider details such as a rejected
     * API key; the full exception goes to the server log.
     */
    static final String AGENT_FAILED = "The agent could not answer. The details are in the server log.";

    /** Shown when the stored conversation cannot be read; the cause goes to the server log. */
    static final String HISTORY_FAILED = "The conversation could not be loaded. The details are in the server log.";

    /** Records failures with their full cause, which the page itself never shows. */
    private static final Logger log = LoggerFactory.getLogger(ChatView.class);

    /** Answers questions; the same bean the HTMX chat and every channel talk to. */
    private final Agent agent;

    /** One child per message, oldest first. */
    private final Div transcript = new Div();

    /** Scrolls the transcript; kept so the view can bring the newest message into sight. */
    private final Scroller scroller = new Scroller(transcript);

    /** Where the question is typed. */
    private final TextArea composer = new TextArea();

    /** Sends the question; disabled by the browser on click until the reply is back. */
    private final Button send = new Button("Send");

    /**
     * Builds the page and shows the conversation as it stands. Spring supplies both arguments.
     *
     * @param agent                the agent that answers, never null
     * @param chatMemoryRepository the agent's conversation store, read once here to show history
     */
    public ChatView(Agent agent, ChatMemoryRepository chatMemoryRepository) {
        this.agent = agent;

        transcript.setId("transcript");
        transcript.addClassName("chat-transcript");
        scroller.setWidthFull();
        addAttachListener(event -> scrollToNewest());

        composer.setId("composer");
        composer.setPlaceholder("Ask anything");
        composer.setWidthFull();

        send.setId("send");
        // The browser greys the button out the moment it is clicked, before the request reaches
        // the server, so a second click cannot queue a second question behind a slow answer.
        send.setDisableOnClick(true);
        send.addClickListener(event -> sendQuestion());

        HorizontalLayout composerRow = new HorizontalLayout(composer, send);
        composerRow.addClassName("chat-composer");
        composerRow.setAlignItems(FlexComponent.Alignment.END);

        setSizeFull();
        add(scroller, composerRow);
        setFlexGrow(1, scroller);

        showHistory(chatMemoryRepository);
    }

    /**
     * Shows the stored user and assistant messages of {@link #CONVERSATION_ID}, the same filter the
     * HTMX chat applies; system and tool messages are not part of what a reader sees.
     *
     * <p>A store that cannot be read leaves an error line instead of failing the whole page, so
     * the composer still works.
     *
     * @param chatMemoryRepository the agent's conversation store
     */
    private void showHistory(ChatMemoryRepository chatMemoryRepository) {
        try {
            for (Message message : chatMemoryRepository.findByConversationId(CONVERSATION_ID)) {
                if (message instanceof UserMessage) {
                    append(Role.USER, message.getText());
                } else if (message instanceof AssistantMessage) {
                    append(Role.ASSISTANT, message.getText());
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not read conversation '{}'", CONVERSATION_ID, e);
            append(Role.ERROR, HISTORY_FAILED);
        }
    }

    /**
     * Sends the composer's text to the agent and shows the question and its reply.
     *
     * <p>Blank input is ignored. A failing agent leaves an error line rather than an exception page,
     * and the button is re-enabled on every path, because the browser disabled it on click.
     */
    private void sendQuestion() {
        try {
            String question = composer.getValue();
            if (question == null || question.isBlank()) {
                return;
            }
            composer.clear();
            append(Role.USER, question);
            try {
                append(Role.ASSISTANT, agent.respondTo(CONVERSATION_ID, question));
            } catch (RuntimeException e) {
                log.error("The agent failed to answer in conversation '{}'", CONVERSATION_ID, e);
                append(Role.ERROR, AGENT_FAILED);
            }
        } finally {
            send.setEnabled(true);
            scrollToNewest();
        }
    }

    /**
     * Scrolls the transcript to its newest message.
     *
     * <p>Not {@link Scroller#scrollToBottom()}: on first load its script runs before the browser has
     * defined and laid out {@code vaadin-scroller}, when there is nothing to scroll yet, and the page
     * stays at the oldest message. Waiting for the element's definition and the next frame is what
     * makes the scroll land.
     */
    private void scrollToNewest() {
        scroller.getElement().executeJs("""
                customElements.whenDefined('vaadin-scroller').then(() =>
                    requestAnimationFrame(() => { this.scrollTop = this.scrollHeight; }));
                """);
    }

    /**
     * Adds one message to the end of the transcript.
     *
     * @param role who the message is from
     * @param text the message text, shown verbatim; null is shown as empty
     */
    private void append(Role role, String text) {
        Span label = new Span(role.label);
        label.addClassName("message-role");

        Div body = new Div();
        body.addClassName("message-text");
        // setText escapes, so markup in a message is displayed, never rendered.
        body.setText(text == null ? "" : text);

        Div message = new Div(label, body);
        message.addClassNames("message", "message-" + role.cssName);
        transcript.add(message);
    }

    /** Who a line in the transcript comes from. */
    private enum Role {
        /** The person typing in this browser. */
        USER("user", "You"),
        /** The agent. */
        ASSISTANT("assistant", "JavaClaw"),
        /** This page, reporting a failure instead of a message. */
        ERROR("error", "Error");

        /** Suffix of the {@code message-*} class name, which styling and tests select on. */
        private final String cssName;

        /** Shown above the message text. */
        private final String label;

        /**
         * Pairs the class name styling and tests select on with the name a reader sees.
         *
         * @param cssName suffix of the message's {@code message-*} class name
         * @param label   visible name shown above the message
         */
        Role(String cssName, String label) {
            this.cssName = cssName;
            this.label = label;
        }
    }
}
