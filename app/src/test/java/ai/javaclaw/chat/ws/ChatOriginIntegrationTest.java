package ai.javaclaw.chat.ws;

import ai.javaclaw.approval.PendingApprovals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static ai.javaclaw.chat.ws.ChatApprovalIntegrationTest.connect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chat socket can answer tool approvals, so only JavaClaw's own pages (and origins listed in
 * {@code agent.web.allowed-origins}) may open it. Checked over a real handshake while an approval
 * is waiting, so a refused page would have something to steal or answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ChatApprovalIntegrationTest.TestConfig.class,
        properties = "agent.web.allowed-origins=https://claw.example.com/")
class ChatOriginIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    PendingApprovals pendingApprovals;

    final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void reset() {
        ChatApprovalIntegrationTest.toolRuns.clear();
    }

    @Test
    void javaClawsOwnOriginCanConnectAndAnswerApprovals() throws Exception {
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(port, "http://localhost:" + port, frames);
        try {
            Map<String, Object> request = startTurnAwaitingApproval(session, frames);
            send(session, decision(request, "allow-once"));

            assertThat(data(frameOfType(frames, "approvalResolved"))).containsEntry("outcome", "allowed");
            frameOfType(frames, "done");
            assertThat(ChatApprovalIntegrationTest.toolRuns).hasSize(1);
        } finally {
            session.close();
        }
    }

    @Test
    void anotherOriginIsRefusedGetsNoFramesAndCannotAnswerAPendingApproval() throws Exception {
        BlockingQueue<Map<String, Object>> owner = new LinkedBlockingQueue<>();
        WebSocketSession ownerSession = connect(port, "http://localhost:" + port, owner);
        try {
            Map<String, Object> request = startTurnAwaitingApproval(ownerSession, owner);
            String approvalId = (String) request.get("approvalId");

            BlockingQueue<Map<String, Object>> evil = new LinkedBlockingQueue<>();
            assertThatThrownBy(() -> connect(port, "http://evil.example", evil))
                    .hasStackTraceContaining("403");

            assertThat(evil.poll(500, TimeUnit.MILLISECONDS))
                    .as("a refused page receives no frames, not even replayed approvals").isNull();
            assertThat(pendingApprovals.isPending(approvalId)).isTrue();
            assertThat(ChatApprovalIntegrationTest.toolRuns).isEmpty();

            send(ownerSession, decision(request, "deny"));
            frameOfType(owner, "done");
        } finally {
            ownerSession.close();
        }
    }

    @Test
    void aHandshakeWithoutAnOriginHeaderIsRefused() {
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();

        assertThatThrownBy(() -> connect(port, null, frames)).hasStackTraceContaining("403");
        assertThat(frames).isEmpty();
    }

    @Test
    void anOriginListedInAllowedOriginsCanConnect() throws Exception {
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(port, "https://claw.example.com", frames);
        try {
            assertThat(session.isOpen()).isTrue();
            Map<String, Object> request = startTurnAwaitingApproval(session, frames);
            send(session, decision(request, "deny"));
            assertThat(data(frameOfType(frames, "approvalResolved"))).containsEntry("detail", "The user did not approve this action.");
            frameOfType(frames, "done");
        } finally {
            session.close();
        }
    }

    private Map<String, Object> startTurnAwaitingApproval(WebSocketSession session,
                                                          BlockingQueue<Map<String, Object>> frames) throws Exception {
        send(session, Map.of("type", "userMessage", "conversationId", "web", "message", "note: buy milk"));
        return data(frameOfType(frames, "approvalRequested"));
    }

    private static Map<String, Object> decision(Map<String, Object> request, String decision) {
        return Map.of("type", "approvalDecision", "conversationId", "web",
                "approvalId", request.get("approvalId"),
                "argumentsHash", request.get("argumentsHash"),
                "decision", decision);
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws Exception {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    private static Map<String, Object> frameOfType(BlockingQueue<Map<String, Object>> frames, String type)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> frame = frames.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && type.equals(frame.get("type"))) {
                return frame;
            }
        }
        throw new AssertionError("No " + type + " frame arrived");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("data");
    }
}
