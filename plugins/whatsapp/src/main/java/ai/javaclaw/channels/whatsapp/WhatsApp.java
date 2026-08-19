package ai.javaclaw.channels.whatsapp;

public class WhatsApp {

    @FunctionalInterface
    interface MessageReceiver {
        void onMessage(String fromJidId, String message);
    }

    public void sendMessage(String jidId, String message) {
        // todo
    }

    public void registerMessageReceiver(MessageReceiver messageReceiver) {
        // todo
    }
}
