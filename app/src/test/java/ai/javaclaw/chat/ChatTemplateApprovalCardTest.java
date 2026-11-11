package ai.javaclaw.chat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval card is built in the browser from the {@code approvalRequested} frame. Its tool
 * name, arguments, details and denial reasons are model output, so none of it may reach the DOM
 * as markup. The build has no browser to render the card in, so this pins the rule on the
 * template's script itself: every server value goes through {@code textContent}, and the only
 * {@code innerHTML} assignments in the card code are constant skeletons.
 */
class ChatTemplateApprovalCardTest {

    /** Fields of the approval frames that carry model or user text. */
    static final List<String> UNTRUSTED_FIELDS = List.of(
            "toolName", "arguments", "protectedReason", "detail", "approvalId", "argumentsHash");

    static String approvalCode;

    @BeforeAll
    static void readCardScript() throws IOException {
        String template = new ClassPathResource("templates/chat.html.peb").getContentAsString(StandardCharsets.UTF_8);
        int start = template.indexOf("// --- Tool approvals");
        int end = template.indexOf("function section(label, extraClass)", start);
        assertThat(start).as("approval card script").isPositive();
        assertThat(end).isGreaterThan(start);
        approvalCode = template.substring(start, end);
    }

    @Test
    void theCardHandlesBothApprovalFrames() {
        assertThat(approvalCode)
                .contains("function renderApprovalRequest(")
                .contains("function renderApprovalResolved(");
    }

    @Test
    void innerHtmlIsOnlyEverAssignedConstantMarkup() {
        Matcher assignments = Pattern.compile("\\.innerHTML\\s*=([^;]*);", Pattern.DOTALL).matcher(approvalCode);
        int count = 0;
        while (assignments.find()) {
            count++;
            String value = assignments.group(1);
            assertThat(value).as("innerHTML assignment %s", value).doesNotContain("data.").doesNotContain("details[");
            // Only string literals and the constant icon may be concatenated
            String withoutLiterals = value.replaceAll("'[^']*'", "").replace("TOOL_ICON", "");
            assertThat(withoutLiterals.replaceAll("[\\s+]", "")).as("non-literal in %s", value).isEmpty();
        }
        assertThat(count).as("the standalone card skeleton").isEqualTo(1);
    }

    @Test
    void serverValuesAreWrittenAsText() {
        assertThat(approvalCode).contains("node.textContent = text");
        for (String field : UNTRUSTED_FIELDS) {
            Matcher uses = Pattern.compile("data\\." + field + "\\b").matcher(approvalCode);
            while (uses.find()) {
                String line = lineAt(uses.start());
                assertThat(line).as("use of data.%s", field)
                        .doesNotContain("innerHTML").doesNotContain("insertAdjacentHTML").doesNotContain("outerHTML");
            }
        }
        assertThat(approvalCode).doesNotContain("insertAdjacentHTML").doesNotContain("outerHTML")
                .doesNotContain("document.write");
    }

    @Test
    void argumentsAreShownInFullNotTruncated() {
        assertThat(approvalCode).contains("pretty(data.arguments)");
    }

    @Test
    void theRequestsOwnArgumentsAreAlwaysInThePanel() {
        assertThat(approvalCode)
                .contains("panel.appendChild(el('pre', 'ar-tool__pre', pretty(data.arguments)));")
                .doesNotContain("if (standalone) {\n            panel.appendChild");
    }

    @Test
    void aReplayedRequestIsSkippedOnlyWhileItsCardIsStillOnThePage() {
        assertThat(approvalCode)
                .contains("(existing && existing.card.isConnected)) return;")
                .doesNotContain("|| approvals[data.approvalId]) return;");
    }

    private static String lineAt(int index) {
        int from = approvalCode.lastIndexOf('\n', index) + 1;
        int to = approvalCode.indexOf('\n', index);
        return approvalCode.substring(from, to < 0 ? approvalCode.length() : to);
    }
}
