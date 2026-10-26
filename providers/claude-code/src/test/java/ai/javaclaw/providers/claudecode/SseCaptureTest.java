package ai.javaclaw.providers.claudecode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SseCaptureTest {

    private static final String START = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n";

    @Test
    void rebuildsTextThinkingAndToolInputAcrossArbitraryChunkBoundaries() {
        String stream = START
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}\r\n\r\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"héllo λ\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"x\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"1}\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
                + ": keepalive\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        SseCapture capture = new SseCapture();
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        // Three-byte chunks split multi-byte characters and separators.
        for (int i = 0; i < bytes.length; i += 3) {
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + 3));
            capture.feed(chunk, chunk.length);
        }

        assertThat(capture.complete()).isTrue();
        assertThat(capture.message().get("content").get(0).get("signature").stringValue()).isEqualTo("sig");
        assertThat(capture.message().get("content").get(1).get("text").stringValue()).isEqualTo("héllo λ");
        assertThat(capture.message().get("content").get(2).get("input").get("a").intValue()).isEqualTo(1);
        assertThat(capture.message().get("usage").get("output_tokens").intValue()).isEqualTo(9);
        assertThat(capture.message().get("usage").get("input_tokens").intValue()).isEqualTo(3);
    }

    @Test
    void dropsAToolCallWhoseArgumentsWereCutOff() {
        String stream = START
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"x\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":9}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        SseCapture capture = new SseCapture();
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        capture.feed(bytes, bytes.length);

        assertThat(capture.complete()).isTrue();
        assertThat(capture.message().get("content")).isEmpty();
    }

    @Test
    void aStreamWithoutMessageStopIsIncomplete() {
        SseCapture capture = new SseCapture();
        byte[] bytes = START.getBytes(StandardCharsets.UTF_8);
        capture.feed(bytes, bytes.length);

        assertThat(capture.complete()).isFalse();
    }
}
