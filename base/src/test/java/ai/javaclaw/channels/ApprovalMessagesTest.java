package ai.javaclaw.channels;

import ai.javaclaw.approval.ApprovalDecision;
import ai.javaclaw.approval.ApprovalRequest;
import ai.javaclaw.approval.Denial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

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

    static final JsonMapper JSON = JsonMapper.builder().build();

    final ApprovalMessages messages = new ApprovalMessages();

    // -----------------------------------------------------------------------
    // What a request says
    // -----------------------------------------------------------------------

    @Test
    void theRequestNamesTheToolListsItsArgumentsAndEndsWithTheCommands() {
        String message = render(request("fetchPage", json(Map.of("url", "https://example.com"))));

        assertThat(message).isEqualTo("""
                <b>JavaClaw wants to run</b> <code>fetchPage</code>
                <pre>  url: https://example.com</pre>
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
        String asked = messages.request(request("fetchPage", json(args)), "abc234", format);

        assertThat(asked.length()).isLessThanOrEqualTo(format.maxLength());
        assertThat(asked).contains(format.text(NOT_SHOWN_IN_FULL)).contains("arg0");
        assertThat(asked.replace("\\", "")).contains("/approve_abc234").contains("/deny_abc234");
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

    @ParameterizedTest
    @EnumSource(ApprovalFormat.class)
    void everyChannelShowsTheCommands(ApprovalFormat format) {
        String message = messages.request(request("fetchPage", json(Map.of("url", "https://example.com"))),
                "abc234", format);

        assertThat(message.replace("\\", "")).contains("/approve_abc234").contains("/deny_abc234");
    }

    // -----------------------------------------------------------------------
    // Outcomes
    // -----------------------------------------------------------------------

    @Test
    void theOutcomeIsOneShortLineWithTheShortId() {
        ApprovalRequest request = request("fetchPage", "{}");

        assertThat(messages.outcome(request, "abc234", null, ApprovalFormat.TELEGRAM_HTML))
                .isEqualTo("✅ Approved: running fetchPage. (abc234)");
        assertThat(messages.outcome(request, "abc234", Denial.NOT_APPROVED, ApprovalFormat.TELEGRAM_HTML))
                .isEqualTo("❌ The user did not approve this action. (abc234)");
        assertThat(messages.outcome(request, "abc234", Denial.MOVED_ON, ApprovalFormat.TELEGRAM_HTML))
                .isEqualTo("❌ The user moved on without approving. (abc234)");
    }

    // -----------------------------------------------------------------------

    private String render(ApprovalRequest request) {
        return messages.request(request, "abc234", ApprovalFormat.TELEGRAM_HTML);
    }

    private static ApprovalRequest request(String tool, String arguments) {
        return new ApprovalRequest("call-1", "call-1", "telegram-42", tool, arguments, "hash",
                List.of(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY));
    }

    private static String json(Map<String, ?> args) {
        return JSON.writeValueAsString(args);
    }
}
