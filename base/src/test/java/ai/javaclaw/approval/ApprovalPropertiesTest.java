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
    void withoutConfigurationNothingIsAllowedAndTheTimeoutIsTwoMinutes() {
        contextRunner.run(context -> {
            ApprovalProperties properties = context.getBean(ApprovalProperties.class);
            assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.allow()).isEmpty();
        });
    }

    @Test
    void bindsAllowAndTimeout() {
        contextRunner
                .withPropertyValues(
                        "agent.approval.timeout=10m",
                        "agent.approval.allow=Read, WebFetch")
                .run(context -> {
                    ApprovalProperties properties = context.getBean(ApprovalProperties.class);
                    assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.allow()).containsExactly("Read", "WebFetch");
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApprovalProperties.class)
    static class PropertiesOnly {
    }
}
