package ai.javaclaw.chat.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WebOriginPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void noExtraOriginsByDefault() {
        runner.run(context -> assertThat(context.getBean(WebOriginProperties.class).allowedOrigins()).isEmpty());
    }

    @Test
    void listedOriginsAreTrimmedAndLoseATrailingSlash() {
        runner.withPropertyValues("agent.web.allowed-origins= https://claw.example.com/ ,http://10.0.0.5:8080")
                .run(context -> assertThat(context.getBean(WebOriginProperties.class).allowedOrigins())
                        .containsExactly("https://claw.example.com", "http://10.0.0.5:8080"));
    }

    @Test
    void aWildcardFailsAtStartup() {
        runner.withPropertyValues("agent.web.allowed-origins=*")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("wildcard"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "claw.example.com", "ftp://claw.example.com",
            "https://claw.example.com/chat", "https://claw.example.com?x=1", "https://user@claw.example.com", ""})
    void anythingButAnExactOriginIsRefused(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebOriginProperties(List.of(origin)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebOriginProperties.class)
    static class Config {
    }
}
