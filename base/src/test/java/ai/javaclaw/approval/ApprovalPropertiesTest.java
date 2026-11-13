package ai.javaclaw.approval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void withoutConfigurationNothingIsAllowed() {
        contextRunner.run(context -> assertThat(context.getBean(ApprovalProperties.class).allow()).isEmpty());
    }

    @Test
    void bindsAllowTrimmedWithoutBlanks() {
        contextRunner
                .withPropertyValues("agent.approval.allow=Read, WebFetch,,")
                .run(context -> assertThat(context.getBean(ApprovalProperties.class).allow())
                        .containsExactly("Read", "WebFetch"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApprovalProperties.class)
    static class PropertiesOnly {
    }
}
