package ai.javaclaw.utils;

/** Describing failures to people. */
public final class Errors {

    private Errors() {
    }

    /** The exception's message, or its simple class name when it has none. */
    public static String summarize(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
