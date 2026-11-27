package ai.javaclaw.files;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlParserTest {

    // -----------------------------------------------------------------------
    // parse — frontmatter only (task file format, no closing ---)
    // -----------------------------------------------------------------------

    @Test
    void serializedMultilineValuesCannotBecomeMetadataOrDelimiters() {
        String description = "First line\nstatus: completed\n---\nRemaining instructions";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", "todo");
        fields.put("description", description);

        YamlDocument parsed = YamlParser.parse(YamlParser.serialize(new YamlDocument(fields, null)));

        assertThat(parsed.frontmatter()).containsEntry("status", "todo")
                .containsEntry("description", description);
        assertThat(parsed.body()).isEmpty();
    }

    @Test
    void roundTripsIndentationInsideMultilineValues() {
        String value = "Start\n  indented: example\n\nEnd";
        YamlDocument parsed = YamlParser.parse(YamlParser.serialize(
                new YamlDocument(Map.of("description", value), "Body")));
        assertThat(parsed.frontmatter().get("description")).isEqualTo(value);
        assertThat(parsed.body()).isEqualTo("Body");
    }

    @Test
    void parseFrontmatterOnlyWithoutClosingDelimiter() {
        String content = """
                ---
                task: handle-email
                createdAt: 2026-03-21T10:00:00Z
                status: todo
                description: Process unread email messages
                """;

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter())
                .containsEntry("task", "handle-email")
                .containsEntry("createdAt", "2026-03-21T10:00:00Z")
                .containsEntry("status", "todo")
                .containsEntry("description", "Process unread email messages");
        assertThat(doc.body()).isBlank();
    }

    @Test
    void parseFrontmatterPreservesInsertionOrder() {
        String content = """
                ---
                alpha: 1
                beta: 2
                gamma: 3
                """;

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter().keySet())
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void parseMultiLineFrontmatterValue() {
        String content = "---\n" +
                "task: my-task\n" +
                "description: First line\n" +
                "second line\n" +
                "third line\n";

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter().get("description"))
                .isEqualTo("First line\nsecond line\nthird line");
    }

    // -----------------------------------------------------------------------
    // parse — body only (chat file format, no frontmatter)
    // -----------------------------------------------------------------------

    @Test
    void parseBodyOnlyWhenNoFrontmatter() {
        String content = "- role: user\n  content: Hello\n";

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter()).isEmpty();
        assertThat(doc.body()).isEqualTo(content);
    }

    // -----------------------------------------------------------------------
    // parse — frontmatter + body (separated by closing ---)
    // -----------------------------------------------------------------------

    @Test
    void parseFrontmatterAndBody() {
        String content = """
                ---
                key: value
                ---
                Body content here.
                """;

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter()).containsEntry("key", "value");
        assertThat(doc.body()).contains("Body content here.");
    }

    // -----------------------------------------------------------------------
    // parse — edge cases
    // -----------------------------------------------------------------------

    @Test
    void parseNullReturnsEmptyDocument() {
        YamlDocument doc = YamlParser.parse(null);

        assertThat(doc.frontmatter()).isEmpty();
        assertThat(doc.body()).isBlank();
    }

    @Test
    void parseBlankReturnsEmptyDocument() {
        YamlDocument doc = YamlParser.parse("   \n  ");

        assertThat(doc.frontmatter()).isEmpty();
        assertThat(doc.body()).isBlank();
    }

    @Test
    void parseStripsTrailingWhitespaceFromFrontmatterValues() {
        String content = "---\ntask: handle-email\n";

        YamlDocument doc = YamlParser.parse(content);

        assertThat(doc.frontmatter().get("task")).isEqualTo("handle-email");
    }

    // -----------------------------------------------------------------------
    // serialize — roundtrip
    // -----------------------------------------------------------------------

    @Test
    void serializeFrontmatterOnlyRoundtrip() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("task", "handle-email");
        fm.put("status", "todo");
        fm.put("description", "Process emails");

        String serialized = YamlParser.serialize(new YamlDocument(fm, null));
        YamlDocument parsed = YamlParser.parse(serialized);

        assertThat(parsed.frontmatter())
                .containsEntry("task", "handle-email")
                .containsEntry("status", "todo")
                .containsEntry("description", "Process emails");
        assertThat(parsed.body()).isBlank();
    }

    @Test
    void serializeBodyOnlyRoundtrip() {
        String body = "- role: user\n  content: Hello\n";

        String serialized = YamlParser.serialize(new YamlDocument(Map.of(), body));
        YamlDocument parsed = YamlParser.parse(serialized);

        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).isEqualTo(body);
    }

    @Test
    void serializeFrontmatterAndBodyRoundtrip() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("key", "value");
        String body = "Body content.\n";

        String serialized = YamlParser.serialize(new YamlDocument(fm, body));
        YamlDocument parsed = YamlParser.parse(serialized);

        assertThat(parsed.frontmatter()).containsEntry("key", "value");
        assertThat(parsed.body()).contains("Body content.");
    }

    @Test
    void serializeEmptyDocumentReturnsEmptyString() {
        String serialized = YamlParser.serialize(new YamlDocument(Map.of(), ""));

        assertThat(serialized).isEmpty();
    }
}
