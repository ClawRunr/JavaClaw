package ai.javaclaw.providers.anthropic;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.backends.Backend;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

class AnthropicClaudeCodeBackend implements Backend {


    private static final String OAUTH_BETA = "claude-code-20250219,oauth-2025-04-20";

    private final AnthropicBackend delegate;


    AnthropicClaudeCodeBackend() {
        // Build delegate with a placeholder token so prepareRequest() adds all standard
        // Anthropic headers (anthropic-version, etc.); we overwrite the auth header ourselves.
        this.delegate = AnthropicBackend.builder().authToken("placeholder").build();
    }

    @Override
    public String baseUrl() {
        return delegate.baseUrl();
    }

    @Override
    public HttpRequest prepareRequest(HttpRequest request) {
        //return delegate.prepareRequest(request);
        HttpRequest prepared = delegate.prepareRequest(request);
        HttpRequestBody originalBody = prepared.body();
        if (originalBody == null) {
            return prepared;
        }
        return prepared.toBuilder()
                .body(new ClaudeCodeRequestBody(originalBody))
                .build();
    }

    @Override
    public HttpRequest authorizeRequest(HttpRequest request) {
        String token = AnthropicClaudeCodeOAuthTokenExtractor
                .getToken()
                .orElseThrow(() -> new IllegalStateException("No valid Claude Code OAuth token found. Run 'claude auth login' to authenticate."));

        HttpRequest prepared = delegate.authorizeRequest(request);
        return prepared.toBuilder()
                .removeHeaders("x-api-key")
                .replaceHeaders("Authorization", "Bearer " + token)
                .putHeader("anthropic-beta", OAUTH_BETA)
                .build();
    }

    @Override
    public HttpResponse prepareResponse(HttpResponse response) {
        return delegate.prepareResponse(response);
    }

    @Override
    public void close() {
        delegate.close();
    }


    /**
     * Wraps the original request body and injects the required Claude Code system
     * prompt prefix in array format with cache_control.
     */
    private static class ClaudeCodeRequestBody implements HttpRequestBody {

        private final ObjectMapper objectMapper;
        private final HttpRequestBody delegate;
        private byte[] modifiedBytes;

        ClaudeCodeRequestBody(HttpRequestBody delegate) {
            this.objectMapper = new ObjectMapper();
            this.delegate = delegate;
        }

        @Override
        public void writeTo(OutputStream os) {
            try {
                if (modifiedBytes == null) {
                    modifiedBytes = buildModifiedBody();
                }
                os.write(modifiedBytes);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write modified request body", e);
            }
        }

        @Override
        public String contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            if (modifiedBytes == null) {
                try {
                    modifiedBytes = buildModifiedBody();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to build modified request body", e);
                }
            }
            return modifiedBytes.length;
        }

        @Override
        public boolean repeatable() {
            return true;
        }

        @Override
        public void close() {
            delegate.close();
        }

        private byte[] buildModifiedBody() throws IOException {
            var baos = new ByteArrayOutputStream();
            delegate.writeTo(baos);
            byte[] original = baos.toByteArray();

            try {
                ObjectNode root = (ObjectNode) objectMapper.readTree(original);
                injectSystemPrefix(root);
                return objectMapper.writeValueAsBytes(root);
            } catch (Exception e) {
                return original;
            }
        }

        private void injectSystemPrefix(ObjectNode root) {
            ArrayNode systemArray = objectMapper.createArrayNode();

            // First block: the required Claude Code prefix with cache_control
            ObjectNode prefixBlock = objectMapper.createObjectNode();
            prefixBlock.put("type", "text");
            prefixBlock.put("text", "You are Claude Code, Anthropic's official CLI for Claude.");
            ObjectNode cacheControl = objectMapper.createObjectNode();
            cacheControl.put("type", "ephemeral");
            prefixBlock.set("cache_control", cacheControl);
            systemArray.add(prefixBlock);

            // Second block: the actual system prompt from the application
            JsonNode existing = root.get("system");
            if (existing != null) {
                if (existing.isString()) {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", existing.asString());
                    systemArray.add(textBlock);
                } else if (existing.isArray()) {
                    for (JsonNode block : existing) {
                        systemArray.add(block);
                    }
                }
            }

            root.set("system", systemArray);
        }
    }
}
