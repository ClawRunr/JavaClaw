package ai.javaclaw.chat.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Puts {@link WebSocketOriginFilter} in front of every request, whichever chat transport is
 * configured, so every WebSocket endpoint (the web chat's and Vaadin's push) gets the same check.
 */
@Configuration
@EnableConfigurationProperties(WebOriginProperties.class)
public class WebSocketOriginConfiguration {

    @Bean
    FilterRegistrationBean<WebSocketOriginFilter> webSocketOriginFilter(WebOriginProperties origins) {
        FilterRegistrationBean<WebSocketOriginFilter> registration =
                new FilterRegistrationBean<>(new WebSocketOriginFilter(origins));
        registration.addUrlPatterns("/*");
        // Before Spring Security, Vaadin and the container's own WebSocket upgrade filter
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
