package ai.javaclaw.providers.anthropic;

import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClaudeCodeBackendTest {

    private AnthropicClaudeCodeBackend backend;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        backend = new AnthropicClaudeCodeBackend();
        objectMapper = new ObjectMapper();
    }

    @Test
    void injectSystemPrefix_whenNoSystemField() throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "claude-sonnet-4-6");
        requestBody.put("max_tokens", 1024);

        HttpRequest prepared = backend.prepareRequest(buildRequest(requestBody));
        JsonNode result = readBodyJson(prepared.body());

        JsonNode system = result.get("system");
        assertThat(system).isNotNull();
        assertThat(system.isArray()).isTrue();
        assertThat(system.size()).isEqualTo(1);

        JsonNode prefixBlock = system.get(0);
        assertThat(prefixBlock.get("type").asString()).isEqualTo("text");
        assertThat(prefixBlock.get("text").asString()).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
        assertThat(prefixBlock.get("cache_control").get("type").asString()).isEqualTo("ephemeral");
    }

    @Test
    void injectSystemPrefix_whenSystemIsString() throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "claude-sonnet-4-6");
        requestBody.put("system", "You are a helpful assistant.");

        HttpRequest prepared = backend.prepareRequest(buildRequest(requestBody));
        JsonNode result = readBodyJson(prepared.body());

        JsonNode system = result.get("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system.size()).isEqualTo(2);

        JsonNode prefixBlock = system.get(0);
        assertThat(prefixBlock.get("type").asString()).isEqualTo("text");
        assertThat(prefixBlock.get("text").asString()).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
        assertThat(prefixBlock.get("cache_control").get("type").asString()).isEqualTo("ephemeral");

        JsonNode originalBlock = system.get(1);
        assertThat(originalBlock.get("type").asString()).isEqualTo("text");
        assertThat(originalBlock.get("text").asString()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void injectSystemPrefix_whenSystemIsArray() throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "claude-sonnet-4-6");

        ArrayNode existingSystem = objectMapper.createArrayNode();
        ObjectNode firstBlock = objectMapper.createObjectNode();
        firstBlock.put("type", "text");
        firstBlock.put("text", "Block one.");
        existingSystem.add(firstBlock);
        ObjectNode secondBlock = objectMapper.createObjectNode();
        secondBlock.put("type", "text");
        secondBlock.put("text", "Block two.");
        existingSystem.add(secondBlock);
        requestBody.set("system", existingSystem);

        HttpRequest prepared = backend.prepareRequest(buildRequest(requestBody));
        JsonNode result = readBodyJson(prepared.body());

        JsonNode system = result.get("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system.size()).isEqualTo(3);

        assertThat(system.get(0).get("text").asString()).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
        assertThat(system.get(0).get("cache_control").get("type").asString()).isEqualTo("ephemeral");
        assertThat(system.get(1).get("text").asString()).isEqualTo("Block one.");
        assertThat(system.get(2).get("text").asString()).isEqualTo("Block two.");
    }

    private HttpRequest buildRequest(ObjectNode body) throws IOException {
        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        HttpRequestBody requestBody = new HttpRequestBody() {
            @Override
            public void writeTo(OutputStream os) {
                try {
                    os.write(bodyBytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String contentType() {
                return "application/json";
            }

            @Override
            public long contentLength() {
                return bodyBytes.length;
            }

            @Override
            public boolean repeatable() {
                return true;
            }

            @Override
            public void close() {
            }
        };

        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl("https://api.anthropic.com")
                .addPathSegment("v1")
                .addPathSegment("messages")
                .body(requestBody)
                .build();
    }

    private JsonNode readBodyJson(HttpRequestBody body) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        body.writeTo(baos);
        return objectMapper.readTree(baos.toByteArray());
    }
}
