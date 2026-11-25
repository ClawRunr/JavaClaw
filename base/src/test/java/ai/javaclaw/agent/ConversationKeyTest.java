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
        assertThat(ConversationKey.task("/workspace/tasks/recurring/x.md").value()).isEqualTo("task:/workspace/tasks/recurring/x.md");
    }

    @Test
    void keysAreEqualByValue() {
        assertThat(ConversationKey.discord("C1")).isEqualTo(ConversationKey.discord("C1"))
                .hasSameHashCodeAs(ConversationKey.discord("C1"))
                .isNotEqualTo(ConversationKey.discord("C2"));
    }

    @Test
    void knowsTheFixedSessionIdItsConversationHadBeforeSessions() {
        assertThat(ConversationKey.web().legacySessionId()).isEqualTo("web");
        assertThat(ConversationKey.telegram(42, null).legacySessionId()).isEqualTo("telegram-42");
        assertThat(ConversationKey.telegram(42, 567).legacySessionId()).isEqualTo("telegram-42-567");
        assertThat(ConversationKey.discord("C1").legacySessionId()).isEqualTo("discord-C1");
        assertThat(ConversationKey.whatsApp("31600000000@s.whatsapp.net").legacySessionId()).isEqualTo("31600000000@s.whatsapp.net");
        assertThat(ConversationKey.teams("conv-1").legacySessionId()).isEqualTo("teams-conv-1");
    }

    @Test
    void refusesAMissingId() {
        assertThatThrownBy(() -> ConversationKey.discord(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationKey.whatsApp(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // A task's legacy session id
    // -----------------------------------------------------------------------

    @Test
    void keepsTheDateDirectorySoTasksOnDifferentDaysDoNotShareAConversation() {
        String monday = legacyId("/workspace/tasks/2026-08-10/103000-buy_milk.md");
        String tuesday = legacyId("/workspace/tasks/2026-08-11/103000-buy_milk.md");

        assertThat(monday).isEqualTo("task-2026-08-10-103000-buy_milk");
        assertThat(tuesday).isEqualTo("task-2026-08-11-103000-buy_milk");
        assertThat(monday).isNotEqualTo(tuesday);
    }

    @Test
    void usesTheNameAloneWhenTheTaskIdHasNoDirectory() {
        String id = legacyId("some-id");

        assertThat(id).isEqualTo("task-some-id");
    }

    @Test
    void namesRecurringTasksAfterTheirOwnDirectory() {
        String id = legacyId("/workspace/tasks/recurring/water_the_plants.md");

        assertThat(id).isEqualTo("task-recurring-water_the_plants");
    }

    @Test
    void replacesCharactersThatCannotSafelyAppearInAFileName() {
        String id = legacyId("/workspace/tasks/2026-08-11/103000-a b:c.md");

        assertThat(id).isEqualTo("task-2026-08-11-103000-a_b_c");
    }

    @Test
    void collapsesTheSeparatorsIntroducedByReplacement() {
        String id = legacyId("/workspace/tasks/2026-08-11/103000-a???b.md");

        assertThat(id).isEqualTo("task-2026-08-11-103000-a_b");
    }

    @Test
    void resolvesRelativeSegmentsBeforeDerivingTheId() {
        String id = legacyId("/workspace/tasks/2026-08-11/../2026-08-10/103000-buy_milk.md");

        assertThat(id).isEqualTo("task-2026-08-10-103000-buy_milk");
    }

    @Test
    void refusesATaskIdItCannotDeriveAnIdFrom() {
        assertThatThrownBy(() -> legacyId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacyId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String legacyId(String taskId) {
        return ConversationKey.task(taskId).legacySessionId();
    }
}
