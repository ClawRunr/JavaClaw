package org.springframework.ai.chat.client.advisor;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.stream.Collectors;

/**
 * A copy of Spring's MessageChatMemoryAdvisor that does not add duplicate messages from memory if they are contained in the chatClientRequest.prompt().getInstructions()
 */
public final class MessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private final ChatMemory chatMemory;

    private final String defaultConversationId;

    private final int order;

    private final Scheduler scheduler;

    private MessageChatMemoryAdvisor(ChatMemory chatMemory, String defaultConversationId, int order, Scheduler scheduler) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        Assert.notNull(scheduler, "scheduler cannot be null");
        this.chatMemory = chatMemory;
        this.defaultConversationId = defaultConversationId;
        this.order = order;
        this.scheduler = scheduler;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String conversationId = getConversationId(chatClientRequest.context(), this.defaultConversationId);

        List<Message> instructions = chatClientRequest.prompt().getInstructions();

        // 1. Remove duplicated messages based on content, ignoring metadata.
        List<Message> processedMessages = deduplicate(this.chatMemory.get(conversationId), instructions);

        // 2.1. Ensure system message, if present, appears first in the list.
        for (int i = 0; i < processedMessages.size(); i++) {
            if (processedMessages.get(i) instanceof SystemMessage) {
                Message systemMessage = processedMessages.remove(i);
                processedMessages.add(0, systemMessage);
                break;
            }
        }

        // 3. Create a new request with the advised messages.
        ChatClientRequest processedChatClientRequest = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
                .build();

        // 4. Add the new user message to the conversation memory.
        Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
        this.chatMemory.add(conversationId, userMessage);

        return processedChatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        List<Message> assistantMessages = new ArrayList<>();
        if (chatClientResponse.chatResponse() != null) {
            assistantMessages = chatClientResponse.chatResponse()
                    .getResults()
                    .stream()
                    .map(g -> (Message) g.getOutput())
                    .toList();
        }
        this.chatMemory.add(this.getConversationId(chatClientResponse.context(), this.defaultConversationId),
                assistantMessages);
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        // Get the scheduler from BaseAdvisor
        Scheduler scheduler = this.getScheduler();

        // Process the request with the before method
        return Mono.just(chatClientRequest)
                .publishOn(scheduler)
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
                        response -> this.after(response, streamAdvisorChain)));
    }

    /**
     * Merges {@code memoryMessages} and {@code instructions} into an ordered list with
     * duplicates removed. Deduplication is based on message content only —
     * {@code metadata} is deliberately ignored because
     * {@link org.springframework.ai.chat.messages.AbstractMessage#equals} includes it,
     * and the persisted copy of a message may have different metadata than the in-flight
     * copy (e.g. tool-call assistant messages with a {@code null} vs {@code ""} text).
     * Memory messages appear first; instructions are appended in order, skipping any
     * that are already present.
     */
    static List<Message> deduplicate(List<Message> memoryMessages, List<Message> instructions) {
        SequencedSet<MessageWrapper> seen = new LinkedHashSet<>(memoryMessages.stream().map(MessageWrapper::new).toList());
        instructions.stream().map(MessageWrapper::new).forEach(seen::add);
        return seen.stream().map(MessageWrapper::message).toList();
    }

    /**
     * Wraps a {@link Message} with {@code equals}/{@code hashCode} based on content
     * only, deliberately ignoring {@code metadata}. This is necessary because
     * {@link org.springframework.ai.chat.messages.AbstractMessage#equals} includes
     * metadata, which may differ between the persisted copy and the in-flight copy
     * of the same logical message.
     */
    private record MessageWrapper(Message message) {

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MessageWrapper w)) return false;
            Message a = this.message, b = w.message;
            if (a.getMessageType() != b.getMessageType()) return false;
            if (a instanceof AssistantMessage am && b instanceof AssistantMessage bm) {
                if (am.hasToolCalls() || bm.hasToolCalls())
                    return Objects.equals(am.getToolCalls(), bm.getToolCalls());
            }
            if (a instanceof ToolResponseMessage tm1 && b instanceof ToolResponseMessage tm2)
                return Objects.equals(tm1.getResponses(), tm2.getResponses());
            return Objects.equals(normalizeText(a), normalizeText(b));
        }

        @Override
        public int hashCode() {
            if (message instanceof AssistantMessage am && am.hasToolCalls())
                return Objects.hash(message.getMessageType(), am.getToolCalls());
            if (message instanceof ToolResponseMessage tm)
                return Objects.hash(message.getMessageType(), tm.getResponses());
            return Objects.hash(message.getMessageType(), normalizeText(message));
        }

        private static String normalizeText(Message m) {
            String t = m.getText();
            return (t == null) ? "" : t;
        }
    }

    public static Builder builder(ChatMemory chatMemory) {
        return new Builder(chatMemory);
    }

    public static final class Builder {

        private String conversationId = ChatMemory.DEFAULT_CONVERSATION_ID;

        private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

        private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

        private final ChatMemory chatMemory;

        private Builder(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
        }

        /**
         * Set the conversation id.
         *
         * @param conversationId the conversation id
         * @return the builder
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        /**
         * Set the order.
         *
         * @param order the order
         * @return the builder
         */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Build the advisor.
         *
         * @return the advisor
         */
        public MessageChatMemoryAdvisor build() {
            return new MessageChatMemoryAdvisor(this.chatMemory, this.conversationId, this.order, this.scheduler);
        }

    }
}