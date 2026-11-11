package ai.javaclaw.channels;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.javaclaw.channels.ApprovalMessages.NOT_SHOWN_IN_FULL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an approval request says and how it is written for each channel. Rendered in Telegram's
 * HTML unless a test is about another channel.
 */
class ApprovalMessagesTest {

    static final Instant NOW = Instant.parse("2026-11-07T11:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();

    final ApprovalMessages messages = new ApprovalMessages(Clock.fixed(NOW, ZoneId.of("Europe/Amsterdam")));

    // -----------------------------------------------------------------------
    // What a request says
    // -----------------------------------------------------------------------

    @Test
    void theRequestNamesTheToolListsItsArgumentsAndEndsWithTheExpiryAndCommands() {
        String message = render(request("fetchPage", json(Map.of("url", "https://example.com"))));

        assertThat(message).isEqualTo("""
                <b>JavaClaw wants to run</b> <code>fetchPage</code>
                <pre>  url: https://example.com</pre>
                Expires at 12:05
                Reply /approve_abc234 or /deny_abc234""");
    }

    @Test
    void everyArgumentIsAnIndentedKeyValueLineWhateverTheTool() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", "ls\ncat notes.md");
        args.put("timeout", 30);
        args.put("options", Map.of("all", true));

        assertThat(render(request("Bash", json(args)))).contains("""
                <pre>  command: ls
                    cat notes.md
                  timeout: 30
                  options: {&quot;all&quot;:true}</pre>""");
    }

    @Test
    void noArgumentsAndArgumentsThatAreNotAnObjectAreStillShown() {
        assertThat(render(request("closeBrowser", "{}"))).contains("<pre>  (none)</pre>");
        assertThat(render(request("odd", "[1,2]"))).contains("<pre>  [1,2]</pre>");
    }

    @Test
    void aProtectedPathIsCalledOut() {
        String message = render(request("Read", json(Map.of("file_path", "workspace/AGENT.md")),
                "touches a protected path (workspace/AGENT.md)"));

        assertThat(message).contains("⚠️ Protected: it touches a protected path (workspace/AGENT.md), so it always needs approval\n"
                + "Expires at");
    }

    @Test
    void aShortRequestIsShownInFullWithoutTheWarning() {
        assertThat(render(request("fetchPage", json(Map.of("url", "https://example.com")))))
                .doesNotContain(NOT_SHOWN_IN_FULL);
    }

    // -----------------------------------------------------------------------
    // Length
    // -----------------------------------------------------------------------

    @Test
    void aLongValueIsCutAt300CharactersAndTheMessageSaysSo() {
        String content = "a".repeat(299) + "bcdef";

        String message = render(request("Write", json(Map.of("content", content))));

        assertThat(message).contains("  content: " + "a".repeat(299) + "b…</pre>\n" + NOT_SHOWN_IN_FULL + "\n")
                .doesNotContain("cdef");
    }

    @Test
    void aValueOfExactly300CharactersIsNotCut() {
        String message = render(request("Write", json(Map.of("content", "x".repeat(300)))));

        assertThat(message).contains("x".repeat(300) + "</pre>").doesNotContain(NOT_SHOWN_IN_FULL);
    }

    /** Characters each channel escapes into something longer, so the escaped text is longest. */
    static String worstCase(ApprovalFormat format) {
        return switch (format) {
            case TELEGRAM_HTML -> "&<>\"";
            case DISCORD_MARKDOWN, TEAMS_MARKDOWN -> "`*_<>&[]";
            case WHATSAPP -> "*_~`";
        };
    }

    @ParameterizedTest
    @EnumSource(ApprovalFormat.class)
    void manyLongArgumentsStayUnderTheChannelLimitAndSayTheyWereCut(ApprovalFormat format) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            args.put("arg" + i, worstCase(format).repeat(200));
        }
        ApprovalRequest request = request("fetchPage", json(args), "touches a protected path (workspace/skills/)");

        String asked = messages.request(request, "abc234", format);
        String resolved = messages.resolvedRequest(request, new Denial(Denial.Reason.USER, "the user denied it"), format);

        assertThat(asked.length()).isLessThanOrEqualTo(format.maxLength());
        assertThat(resolved.length()).isLessThanOrEqualTo(format.maxLength());
        assertThat(asked).contains(format.text(NOT_SHOWN_IN_FULL)).contains("arg0");
        assertThat(asked.replace("\\", "")).contains("/approve_abc234").contains("/deny_abc234")
                .contains("Protected: it touches a protected path");
        assertThat(resolved).contains(format.text(NOT_SHOWN_IN_FULL));
    }

    @ParameterizedTest
    @EnumSource(ApprovalFormat.class)
    void aHugeToolNameAndHugeArgumentsThatAreNotJsonStayUnderTheLimit(ApprovalFormat format) {
        String huge = worstCase(format).repeat(5_000);

        String message = messages.request(request(huge, huge), "abc234", format);

        assertThat(message.length()).isLessThanOrEqualTo(format.maxLength());
        assertThat(message).contains(format.text(NOT_SHOWN_IN_FULL));
    }

    @Test
    void discordsLimitIsTheSmallest() {
        assertThat(ApprovalFormat.TELEGRAM_HTML.maxLength()).isEqualTo(4096);
        assertThat(ApprovalFormat.DISCORD_MARKDOWN.maxLength()).isEqualTo(2000);
        assertThat(ApprovalFormat.TEAMS_MARKDOWN.maxLength()).isEqualTo(4000);
        assertThat(ApprovalFormat.WHATSAPP.maxLength()).isEqualTo(4000);
    }

    // -----------------------------------------------------------------------
    // Escaping per channel
    // -----------------------------------------------------------------------

    static final String HOSTILE = "<b>hi</b> **bold** _it_ `tick` ```fence``` [x](https://evil.example) @everyone";

    @Test
    void telegramGetsToolTextAsEscapedHtml() {
        String message = render(request("<b>tool</b>", json(Map.of("content", HOSTILE))));

        assertThat(message).contains("<code>&lt;b&gt;tool&lt;/b&gt;</code>")
                .contains("<pre>  content: &lt;b&gt;hi&lt;/b&gt; **bold**").doesNotContain("<b>hi</b>");
    }

    @Test
    void discordGetsMarkdownEscapedAndCodeThatCannotBeClosedEarly() {
        String message = messages.request(request("ev*l_<tool>`", json(Map.of("note", HOSTILE))), "abc234",
                ApprovalFormat.DISCORD_MARKDOWN);

        assertThat(message).startsWith("**JavaClaw wants to run** `` ev*l_<tool>`​ ``");
        String block = message.substring(message.indexOf("```\n"), message.lastIndexOf("\n```") + 4);
        // No run of backticks inside the block that could end it
        assertThat(block.substring(4, block.length() - 4)).doesNotContain("``");
        assertThat(message).contains("/approve\\_abc234");
    }

    @Test
    void whatsAppKeepsToolTextInMonospace() {
        String message = messages.request(request("Write", json(Map.of("content", HOSTILE))), "abc234",
                ApprovalFormat.WHATSAPP);

        assertThat(message).startsWith("*JavaClaw wants to run* ```Write```")
                .contains("```  content: <b>hi</b> **bold** _it_ `​tick`​ `​`​`​fence")
                .endsWith("Reply /approve_abc234 or /deny_abc234");
    }

    @Test
    void teamsEscapesMarkdownAndHtmlOutsideCode() {
        String message = messages.request(request("Write", "{}", "touches <img src=x>"), "abc234",
                ApprovalFormat.TEAMS_MARKDOWN);

        assertThat(message).contains("touches &lt;img src=x&gt;").doesNotContain("<img");
    }

    @ParameterizedTest
    @EnumSource(ApprovalFormat.class)
    void everyChannelShowsTheCommandsAndTheExpiry(ApprovalFormat format) {
        String message = messages.request(request("fetchPage", json(Map.of("url", "https://example.com"))),
                "abc234", format);

        assertThat(message.replace("\\", "")).contains("/approve_abc234").contains("/deny_abc234").contains("12:05");
    }

    // -----------------------------------------------------------------------
    // Outcomes
    // -----------------------------------------------------------------------

    @Test
    void theResolvedRequestKeepsTheArgumentsAndReplacesTheCommandsWithTheOutcome() {
        ApprovalRequest request = request("fetchPage", json(Map.of("url", "https://example.com")));

        String approved = messages.resolvedRequest(request, null, ApprovalFormat.TELEGRAM_HTML);
        String expired = messages.resolvedRequest(request,
                new Denial(Denial.Reason.TIMEOUT, "nobody approved it within 2 minutes"), ApprovalFormat.TELEGRAM_HTML);

        assertThat(approved).contains("<pre>  url: https://example.com</pre>")
                .endsWith("✅ <b>Approved</b>: running fetchPage.")
                .doesNotContain("/approve_").doesNotContain("Expires at");
        assertThat(expired).endsWith("⌛ <b>Expired</b>: fetchPage was not run because nobody approved it within 2 minutes.");
    }

    @Test
    void theOutcomeReplyNamesTheShortId() {
        ApprovalRequest request = request("fetchPage", "{}");

        assertThat(messages.outcomeReply(request, "abc234", new Denial(Denial.Reason.USER, "the user denied it"),
                ApprovalFormat.TELEGRAM_HTML)).isEqualTo("❌ <b>Denied</b>: fetchPage was not run. (abc234)");
    }

    // -----------------------------------------------------------------------

    private String render(ApprovalRequest request) {
        return messages.request(request, "abc234", ApprovalFormat.TELEGRAM_HTML);
    }

    private static ApprovalRequest request(String tool, String arguments) {
        return request(tool, arguments, null);
    }

    private static ApprovalRequest request(String tool, String arguments, String protectedReason) {
        return new ApprovalRequest("call-1", "call-1", "telegram-42", tool, arguments, "hash",
                List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY), protectedReason, NOW.plusSeconds(300));
    }

    private static String json(Map<String, ?> args) {
        return JSON.writeValueAsString(args);
    }
}
