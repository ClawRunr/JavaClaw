package ai.javaclaw.approval;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the tool approval policy. See {@link ApprovalPolicy}. */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    @Bean
    ApprovalPolicy approvalPolicy(ApprovalProperties properties,
                                  ObjectProvider<ObservationRegistry> observationRegistry,
                                  ObjectProvider<ToolCallbackResolver> toolCallbackResolver) {
        return new ApprovalPolicy(properties, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
                toolCallbackResolver.getIfUnique(() -> null));
    }
}
