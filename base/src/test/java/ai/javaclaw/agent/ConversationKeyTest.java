package ai.javaclaw.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationKeyTest {

    @Test
    void eachChannelBuildsItsOwnKey() {
        assertThat(ConversationKey.web().value()).isEqualTo("web");
        assertThat(ConversationKey.telegram(42, null).value()).isEqualTo("telegram:42");
        assertThat(ConversationKey.telegram(42, 7).value()).isEqualTo("telegram:42:7");
        assertThat(ConversationKey.discord("C1").value()).isEqualTo("discord:C1");
        assertThat(ConversationKey.whatsApp("31600000000@s.whatsapp.net").value()).isEqualTo("whatsapp:31600000000@s.whatsapp.net");
        assertThat(ConversationKey.teams("a:1").value()).isEqualTo("teams:a:1");
    }

    @Test
    void keysAreEqualByValue() {
        assertThat(ConversationKey.discord("C1")).isEqualTo(ConversationKey.discord("C1"))
                .hasSameHashCodeAs(ConversationKey.discord("C1"))
                .isNotEqualTo(ConversationKey.discord("C2"));
    }

    @Test
    void refusesAMissingId() {
        assertThatThrownBy(() -> ConversationKey.discord(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationKey.whatsApp(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // A task's key
    // -----------------------------------------------------------------------

    @Test
    void aTasksKeyIsReadableAndDoesNotDependOnWhereTheWorkspaceIs() {
        ConversationKey here = ConversationKey.task("/home/me/javaclaw/workspace/tasks/recurring/daily-summary.md");
        ConversationKey there = ConversationKey.task("/srv/other/workspace/tasks/recurring/daily-summary.md");

        assertThat(here).isEqualTo(there);
        assertThat(here.value()).isEqualTo("task:recurring-daily-summary")
                .doesNotContain("/home/me")
                .doesNotContain("/");
    }

    @Test
    void keepsTheDateDirectorySoTasksOnDifferentDaysDoNotShareAConversation() {
        String monday = key("/workspace/tasks/2026-08-10/103000-buy_milk.md");
        String tuesday = key("/workspace/tasks/2026-08-11/103000-buy_milk.md");

        assertThat(monday).isEqualTo("task:2026-08-10-103000-buy_milk");
        assertThat(tuesday).isEqualTo("task:2026-08-11-103000-buy_milk");
    }

    @Test
    void usesTheNameAloneWhenTheTaskIdHasNoDirectory() {
        assertThat(key("some-id")).isEqualTo("task:some-id");
    }

    @Test
    void replacesCharactersThatCannotSafelyAppearInAFileName() {
        assertThat(key("/workspace/tasks/2026-08-11/103000-a b:c.md")).isEqualTo("task:2026-08-11-103000-a_b_c");
    }

    @Test
    void collapsesTheSeparatorsIntroducedByReplacement() {
        assertThat(key("/workspace/tasks/2026-08-11/103000-a???b.md")).isEqualTo("task:2026-08-11-103000-a_b");
    }

    @Test
    void resolvesRelativeSegmentsBeforeDerivingTheKey() {
        assertThat(key("/workspace/tasks/2026-08-11/../2026-08-10/103000-buy_milk.md")).isEqualTo("task:2026-08-10-103000-buy_milk");
    }

    @Test
    void refusesATaskIdItCannotDeriveAKeyFrom() {
        assertThatThrownBy(() -> key(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String key(String taskId) {
        return ConversationKey.task(taskId).value();
    }
}
