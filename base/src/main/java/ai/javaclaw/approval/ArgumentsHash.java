package ai.javaclaw.approval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Binds an approval to exactly what was shown: SHA-256 over the tool name and its arguments as
 * canonical JSON (object keys sorted, no insignificant whitespace). Arguments that are not JSON are
 * hashed as given.
 */
public final class ArgumentsHash {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ArgumentsHash() {
    }

    public static String of(String toolName, String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(toolName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(canonicalJson(arguments).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String canonicalJson(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(sorted(JSON.readValue(arguments, Object.class)));
        } catch (JacksonException e) {
            return arguments;
        }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), sorted(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return value;
    }
}
