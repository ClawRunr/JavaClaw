package ai.javaclaw.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reading JSON that is expected to be an object, such as a tool call's arguments. */
public final class JsonObjects {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonObjects() {
    }

    /**
     * @return the object's members in their original order, or an empty map when {@code json} is
     *         blank, not valid JSON, or valid JSON that is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, Object.class) instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}
