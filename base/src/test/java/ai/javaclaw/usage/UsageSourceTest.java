package ai.javaclaw.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UsageSourceTest {

    @ParameterizedTest
    @CsvSource({
            "web, WEB_CHAT",
            "web-session-1, WEB_CHAT",
            "telegram-123456, TELEGRAM",
            "telegram-123-99, TELEGRAM",
            "discord-987654, DISCORD",
            "task-2026-11-25-100000-buy_milk, SCHEDULED_TASK",
            "1234567890@s.whatsapp.net, WHATSAPP",
            "120363@g.us, WHATSAPP",
            "whatsapp-chat, WHATSAPP",
            "something-else, UNKNOWN",
            "'', UNKNOWN"
    })
    void resolvesSourceFromConversationId(String conversationId, UsageSource expected) {
        assertThat(UsageSource.fromConversationId(conversationId)).isEqualTo(expected);
    }

    @Test
    void nullConversationIdIsUnknown() {
        assertThat(UsageSource.fromConversationId(null)).isEqualTo(UsageSource.UNKNOWN);
    }

    @Test
    void taskIdOnlyForScheduledTasks() {
        assertThat(UsageSource.taskIdFor("task-2026-11-25-100000-buy_milk"))
                .isEqualTo("task-2026-11-25-100000-buy_milk");
        assertThat(UsageSource.taskIdFor("telegram-1")).isNull();
        assertThat(UsageSource.taskIdFor("web")).isNull();
    }

    @Test
    void wireNameRoundTrip() {
        for (UsageSource source : UsageSource.values()) {
            assertThat(UsageSource.fromWireName(source.wireName())).isEqualTo(source);
        }
        assertThat(UsageSource.fromWireName("nope")).isEqualTo(UsageSource.UNKNOWN);
    }
}
