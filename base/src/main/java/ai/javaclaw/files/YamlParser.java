package ai.javaclaw.files;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and serialises {@link YamlDocument} instances.
 *
 * <p>This is a lightweight custom parser for the project's own file formats — it is
 * intentionally <em>not</em> a full YAML parser.  The rules are:
 * <ol>
 *   <li>A file optionally starts with a frontmatter block delimited by {@code ---}.</li>
 *   <li>Inside the frontmatter, each line is either {@code key: value} or a continuation
 *       of the previous value (any line that does not match {@code <word>: }).
 *       Serialized multiline values use {@code key: |} with two-space-indented lines.</li>
 *   <li>The frontmatter ends at the next lone {@code ---} line, or at EOF if no closing
 *       delimiter is present.</li>
 *   <li>Everything after the closing {@code ---} is the body, returned verbatim.</li>
 *   <li>If the file does not start with {@code ---}, the whole content is the body.</li>
 * </ol>
 */
public class YamlParser {

    public static final String FRONTMATTER_MARKER = "---";

    private YamlParser() {}

    public static YamlDocument parse(String content) {
        if (content == null || content.isBlank()) {
            return new YamlDocument(Map.of(), "");
        }

        String normalized = content.replace("\r\n", "\n");

        if (!normalized.startsWith(FRONTMATTER_MARKER)) {
            return new YamlDocument(Map.of(), content);
        }

        // Skip the opening "---\n" (4 chars) or "---" at EOF
        int afterOpen = normalized.indexOf('\n');
        if (afterOpen < 0) {
            return new YamlDocument(Map.of(), "");
        }
        String rest = normalized.substring(afterOpen + 1);

        // Look for the closing "---" on its own line
        int closingIdx = findClosingDelimiter(rest);

        if (closingIdx < 0) {
            // No closing delimiter — everything is frontmatter, body is empty
            return new YamlDocument(parseFrontmatterBlock(rest), "");
        }

        String fmBlock = rest.substring(0, closingIdx);
        String body = rest.substring(closingIdx + 4); // skip "---\n"
        return new YamlDocument(parseFrontmatterBlock(fmBlock), body);
    }

    public static String serialize(YamlDocument doc) {
        StringBuilder sb = new StringBuilder();

        boolean hasFrontmatter = doc.frontmatter() != null && !doc.frontmatter().isEmpty();
        boolean hasBody = doc.body() != null && !doc.body().isBlank();

        if (hasFrontmatter) {
            sb.append(FRONTMATTER_MARKER).append(System.lineSeparator());
            doc.frontmatter().forEach((k, v) -> {
                String value = v == null ? "null" : v.replace("\r\n", "\n");
                sb.append(k).append(": ");
                if (value.contains("\n")) {
                    sb.append("|").append(System.lineSeparator());
                    for (String line : value.split("\n", -1)) {
                        sb.append("  ").append(line).append(System.lineSeparator());
                    }
                } else {
                    sb.append(value).append(System.lineSeparator());
                }
            });
        }

        if (hasBody) {
            if (hasFrontmatter) {
                sb.append(FRONTMATTER_MARKER).append(System.lineSeparator());
            }
            sb.append(doc.body());
        }

        return sb.toString();
    }

    // --- private helpers ---

    private static int findClosingDelimiter(String text) {
        // "---\n" or "---" at the very end
        int idx = 0;
        while (idx < text.length()) {
            if (text.startsWith(FRONTMATTER_MARKER, idx)) {
                // Must be at the start of a line (idx == 0 or preceded by \n)
                if (idx == 0 || text.charAt(idx - 1) == '\n') {
                    int end = idx + 3;
                    if (end >= text.length() || text.charAt(end) == '\n') {
                        return idx;
                    }
                }
            }
            int next = text.indexOf('\n', idx);
            if (next < 0) break;
            idx = next + 1;
        }
        return -1;
    }

    private static Map<String, String> parseFrontmatterBlock(String block) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = block.split("\n", -1);
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        boolean literalValue = false;
        boolean hasLiteralLine = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            // Raw key: value line (unindented, contains ": ")
            int colonIdx = line.indexOf(": ");
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (currentKey != null) {
                    result.put(currentKey, currentValue.toString().stripTrailing());
                }
                currentKey = line.substring(0, colonIdx);
                String value = line.substring(colonIdx + 2);
                literalValue = value.equals("|") && index + 1 < lines.length && lines[index + 1].startsWith("  ");
                hasLiteralLine = false;
                currentValue = new StringBuilder(literalValue ? "" : value);
            } else if (currentKey != null) {
                // Continuation line of a multi-line value
                if (literalValue ? hasLiteralLine : currentValue.length() > 0) {
                    currentValue.append('\n');
                }
                currentValue.append(literalValue && line.startsWith("  ") ? line.substring(2) : line);
                hasLiteralLine = true;
            }
        }

        if (currentKey != null) {
            result.put(currentKey, currentValue.toString().stripTrailing());
        }

        return result;
    }
}
