package ai.javaclaw.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void defaultsAskForTwoMinutesAndDenyWhenNobodyCanBeAsked() {
        contextRunner.run(context -> {
            ApprovalProperties properties = context.getBean(ApprovalProperties.class);
            assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.askFallback()).isEqualTo(ApprovalProperties.AskFallback.DENY);
            assertThat(properties.rules().allow()).isEmpty();
            assertThat(properties.rules().ask()).isEmpty();
            assertThat(properties.rules().deny()).isEmpty();
        });
    }

    @Test
    void bindsRulesFallbackAndTimeout() {
        contextRunner
                .withPropertyValues(
                        "agent.approval.timeout=10m",
                        "agent.approval.ask-fallback=allow",
                        "agent.approval.rules.allow=Read,WebFetch",
                        "agent.approval.rules.ask[0]=Write",
                        "agent.approval.rules.deny= Task ")
                .run(context -> {
                    ApprovalProperties properties = context.getBean(ApprovalProperties.class);
                    assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.askFallback()).isEqualTo(ApprovalProperties.AskFallback.ALLOW);
                    assertThat(properties.rules().allow()).containsExactly("Read", "WebFetch");
                    assertThat(properties.rules().ask()).containsExactly("Write");
                    assertThat(properties.rules().deny()).containsExactly("Task");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"11m", "10m1s", "0s", "-5s", "PT1H", "soon"})
    void invalidTimeoutsFailAtStartup(String timeout) {
        contextRunner
                .withPropertyValues("agent.approval.timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOfAny(IllegalArgumentException.class,
                                org.springframework.core.convert.ConversionFailedException.class));
    }

    @Test
    void anUnknownFallbackFailsAtStartup() {
        contextRunner
                .withPropertyValues("agent.approval.ask-fallback=maybe")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApprovalProperties.class)
    static class PropertiesOnly {
    }
}
