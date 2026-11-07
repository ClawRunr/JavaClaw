package ai.javaclaw.channels;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.approval.ToolRisk;
import ai.javaclaw.mcp.McpToolDirectory.McpTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an approval request says, per tool, and how it is written for each channel. Rendered in
 * Telegram's HTML unless a test is about another channel.
 */
class ApprovalMessagesTest {

    static final Instant NOW = Instant.parse("2026-11-07T11:00:00Z");

    @TempDir
    Path workspace;

    ApprovalMessages messages() {
        Map<String, McpTool> mcp = Map.of("github_create_issue", new McpTool("github", "create_issue"));
        return new ApprovalMessages(workspace, tool -> Optional.ofNullable(mcp.get(tool)),
                Clock.fixed(NOW, ZoneId.of("Europe/Amsterdam")));
    }

    // -----------------------------------------------------------------------
    // Titles and details per tool
    // -----------------------------------------------------------------------

    @Test
    void writingANewFileShowsTheWorkspacePathItsSizeAndAPreview() {
        String message = render(request("Write", ToolRisk.WRITE,
                args("filePath", workspace.resolve("notes/todo.md").toString(), "content", "buy milk\n")));

        assertThat(message).startsWith("<b>Approval needed: write a file</b>\n")
                .contains("Path: <code>workspace/notes/todo.md</code>")
                .contains("Size: new file, 9 B")
                .contains("Content:\n<pre>buy milk</pre>")
                .doesNotContain("⚠️")
                .doesNotContain("filePath");
    }

    @Test
    void overwritingAFileShowsTheSizeBeforeAndAfterAndWarns() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "x".repeat(2048));

        String message = render(request("Write", ToolRisk.WRITE,
                args("filePath", workspace.resolve("notes.md").toString(), "content", "short")));

        assertThat(message).contains("Size: 2.0 KB → 5 B").contains("⚠️ Overwrites an existing file");
    }

    @Test
    void editingShowsTheOldAndNewTextAndTheSizeBeforeAndAfter() throws Exception {
        Files.writeString(workspace.resolve("plan.md"), "step one\nstep two\n");

        String message = render(request("Edit", ToolRisk.WRITE, args("filePath", workspace.resolve("plan.md").toString(),
                "old_string", "step two", "new_string", "step two and three")));

        assertThat(message).startsWith("<b>Approval needed: edit a file</b>")
                .contains("Path: <code>workspace/plan.md</code>")
                .contains("Size: 18 B → 28 B")
                .contains("Old text:\n<pre>step two</pre>")
                .contains("New text:\n<pre>step two and three</pre>");
    }

    @Test
    void webFetchShowsTheUrlAndWebSearchTheQuery() {
        assertThat(render(request("WebFetch", ToolRisk.NETWORK, args("url", "https://example.com/a?b=1", "prompt", "summarize"))))
                .startsWith("<b>Approval needed: fetch a web page</b>")
                .contains("URL: <code>https://example.com/a?b=1</code>")
                .doesNotContain("summarize");
        assertThat(render(request("WebSearch", ToolRisk.NETWORK, args("query", "javaclaw release notes"))))
                .startsWith("<b>Approval needed: search the web</b>")
                .contains("Query: <code>javaclaw release notes</code>");
    }

    @Test
    void aSubagentShowsItsTypeAndPrompt() {
        String message = render(request("Task", ToolRisk.EXECUTE, args("subagent_type", "researcher",
                "prompt", "Find the three newest releases", "description", "releases")));

        assertThat(message).startsWith("<b>Approval needed: start a subagent</b>")
                .contains("Subagent: <code>researcher</code>")
                .contains("Prompt:\n<pre>Find the three newest releases</pre>");
    }

    @Test
    void anMcpToolShowsItsServerToolAndArgumentsAsKeyValueLines() {
        String message = render(request("github_create_issue", null,
                "{\"repo\":\"acme/app\",\"title\":\"Crash\",\"labels\":[\"bug\"]}"));

        assertThat(message).startsWith("<b>Approval needed: use an MCP tool</b>")
                .contains("Server: <code>github</code>")
                .contains("Tool: <code>create_issue</code>")
                .contains("Arguments:\n<pre>  repo: acme/app\n  title: Crash\n  labels: [&quot;bug&quot;]</pre>")
                .contains("Risk: unclassified");
    }

    @Test
    void anUnknownToolIsRunByNameWithItsArgumentsAsKeyValueLines() {
        String message = render(request("rotateKeys", null, "{\"service\":\"billing\",\"force\":true}"));

        assertThat(message).startsWith("<b>Approval needed: run rotateKeys</b>")
                .contains("<pre>  service: billing\n  force: true</pre>");
    }

    @Test
    void addingAnMcpServerSaysSoAndShowsWhyItIsProtected() {
        ApprovalRequest request = request("addStdioMcpServer", ToolRisk.EXECUTE, args("name", "fs", "command", "npx"),
                "adds or changes an MCP server");

        assertThat(render(request)).startsWith("<b>Approval needed: add an MCP server</b>")
                .contains("⚠️ Protected: it adds or changes an MCP server, so it always needs approval");
    }

    // -----------------------------------------------------------------------
    // Paths, previews, expiry
    // -----------------------------------------------------------------------

    @Test
    void aPathOutsideTheWorkspaceIsAbsoluteWithAWarning() {
        Path outside = workspace.getParent().resolve("elsewhere/secrets.txt").toAbsolutePath().normalize();

        String message = render(request("Write", ToolRisk.WRITE, args("filePath", outside.toString(), "content", "x")));

        assertThat(message).contains("Path: <code>" + outside + "</code>").contains("⚠️ Outside the workspace");
    }

    @Test
    void aPathThatClimbsOutOfTheWorkspaceIsOutside() {
        String sneaky = workspace.resolve("notes/../../escape.txt").toString();

        assertThat(render(request("Write", ToolRisk.WRITE, args("filePath", sneaky, "content", "x"))))
                .contains("⚠️ Outside the workspace").doesNotContain("workspace/");
    }

    @Test
    void previewsStopAtSixLinesAndSayHowManyAreLeft() {
        String content = IntStream.rangeClosed(1, 10).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));

        ApprovalMessages.Preview preview = ApprovalMessages.preview(content);

        assertThat(preview.shown()).isEqualTo("line 1\nline 2\nline 3\nline 4\nline 5\nline 6");
        assertThat(preview.more()).isEqualTo("(+4 more lines)");
        assertThat(render(request("Write", ToolRisk.WRITE, args("filePath", workspace.resolve("a.md").toString(),
                "content", content)))).contains("line 6</pre>\n(+4 more lines)");
    }

    @Test
    void previewsStopAtThreeHundredCharacters() {
        ApprovalMessages.Preview oneLongLine = ApprovalMessages.preview("y".repeat(1000));
        assertThat(oneLongLine.shown()).isEqualTo("y".repeat(300) + "…");
        assertThat(oneLongLine.more()).isEqualTo("(+700 more characters)");

        String threeLongLines = ("z".repeat(200) + "\n").repeat(3);
        ApprovalMessages.Preview preview = ApprovalMessages.preview(threeLongLines);
        assertThat(preview.shown()).isEqualTo("z".repeat(200) + "\n" + "z".repeat(100) + "…");
        assertThat(preview.more()).isEqualTo("(+1 more line)");

        assertThat(ApprovalMessages.preview("short").more()).isNull();
    }

    @Test
    void theExpiryIsAClockTimeInTheInstancesTimeZone() {
        // 11:05 UTC is 12:05 in Amsterdam in November
        assertThat(render(request("WebFetch", ToolRisk.NETWORK, args("url", "https://example.com"))))
                .contains("\nExpires at 12:05\n");
    }

    @Test
    void sizesReadNaturally() {
        assertThat(ApprovalMessages.human(0)).isEqualTo("0 B");
        assertThat(ApprovalMessages.human(1536)).isEqualTo("1.5 KB");
        assertThat(ApprovalMessages.human(3 * 1024 * 1024)).isEqualTo("3.0 MB");
    }

    // -----------------------------------------------------------------------
    // Escaping per channel
    // -----------------------------------------------------------------------

    static final String HOSTILE = "<b>hi</b> **bold** _it_ `tick` ```fence``` [x](https://evil.example) @everyone";

    @Test
    void telegramGetsToolTextAsEscapedHtml() {
        String message = render(request("Write", ToolRisk.WRITE,
                args("filePath", workspace.resolve("a.md").toString(), "content", HOSTILE)));

        assertThat(message).contains("<pre>&lt;b&gt;hi&lt;/b&gt; **bold**").doesNotContain("<b>hi</b>");
    }

    @Test
    void discordGetsMarkdownEscapedAndCodeThatCannotBeClosedEarly() {
        String message = messages().request(request("ev*l_<tool>`", null, args("note", HOSTILE)), "abc234",
                ApprovalFormat.DISCORD_MARKDOWN);

        assertThat(message).startsWith("**Approval needed: run ev\\*l\\_\\<tool\\>\\`**");
        String block = message.substring(message.indexOf("```\n"), message.lastIndexOf("\n```") + 4);
        // No run of backticks inside the block that could end it
        assertThat(block.substring(4, block.length() - 4)).doesNotContain("``");
        assertThat(message).contains("/approve\\_abc234");
    }

    @Test
    void whatsAppKeepsToolTextInMonospace() {
        String message = messages().request(request("Write", ToolRisk.WRITE,
                args("filePath", workspace.resolve("a.md").toString(), "content", HOSTILE)), "abc234", ApprovalFormat.WHATSAPP);

        assertThat(message).startsWith("*Approval needed: write a file*")
                .contains("```<b>hi</b> **bold** _it_ `\u200Btick`\u200B `\u200B`\u200B`\u200Bfence")
                .endsWith("Reply /approve_abc234 or /deny_abc234");
    }

    @Test
    void teamsEscapesMarkdownAndHtmlOutsideCode() {
        String message = messages().request(request("<img src=x>", null, "{}"), "abc234", ApprovalFormat.TEAMS_MARKDOWN);

        assertThat(message).startsWith("**Approval needed: run &lt;img src=x&gt;**").doesNotContain("<img");
    }

    @ParameterizedTest
    @EnumSource(ApprovalFormat.class)
    void everyChannelShowsTheCommandsAndTheExpiry(ApprovalFormat format) {
        String message = messages().request(request("WebFetch", ToolRisk.NETWORK, args("url", "https://example.com")),
                "abc234", format);

        assertThat(message.replace("\\", "")).contains("/approve_abc234").contains("/deny_abc234").contains("12:05");
    }

    // -----------------------------------------------------------------------
    // Outcomes
    // -----------------------------------------------------------------------

    @Test
    void theResolvedRequestKeepsTheDetailsAndReplacesTheCommandsWithTheOutcome() {
        ApprovalRequest request = request("WebFetch", ToolRisk.NETWORK, args("url", "https://example.com"));

        String approved = messages().resolvedRequest(request, null, ApprovalFormat.TELEGRAM_HTML);
        String expired = messages().resolvedRequest(request,
                new Denial(Denial.Reason.TIMEOUT, "nobody approved it within 2 minutes"), ApprovalFormat.TELEGRAM_HTML);

        assertThat(approved).contains("URL: <code>https://example.com</code>").endsWith("✅ <b>Approved</b>: running WebFetch.")
                .doesNotContain("/approve_").doesNotContain("Expires at");
        assertThat(expired).endsWith("⌛ <b>Expired</b>: WebFetch was not run because nobody approved it within 2 minutes.");
    }

    // -----------------------------------------------------------------------

    private String render(ApprovalRequest request) {
        return messages().request(request, "abc234", ApprovalFormat.TELEGRAM_HTML);
    }

    private static ApprovalRequest request(String tool, ToolRisk risk, String arguments) {
        return request(tool, risk, arguments, null);
    }

    private static ApprovalRequest request(String tool, ToolRisk risk, String arguments, String protectedReason) {
        return new ApprovalRequest("call-1", "call-1", "telegram-42", tool, arguments, risk, "hash",
                List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY), Map.of(), protectedReason,
                NOW.plusSeconds(300));
    }

    private static String args(String... keyValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(keyValues[i]).append("\":")
                    .append(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(keyValues[i + 1]));
        }
        return json.append('}').toString();
    }
}
