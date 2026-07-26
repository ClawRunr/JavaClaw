package ai.javaclaw.agent;

public interface Agent {

    String respondTo(String conversationId, String question);

    default String respondTo(String conversationId, String question, ResponseListener listener) {
        String response = respondTo(conversationId, question);
        listener.onToken(response);
        listener.onComplete();
        return response;
    }

    <T> T prompt(String conversationId, String input, Class<T> result);

}
