package ai.javaclaw.approval;

import ai.javaclaw.utils.Hashes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
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
        // The NUL keeps "ab" + "c" and "a" + "bc" apart
        return Hashes.sha256Hex(toolName + "\0" + canonicalJson(arguments));
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
