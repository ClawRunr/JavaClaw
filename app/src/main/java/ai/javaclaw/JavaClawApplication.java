package ai.javaclaw;

import ai.javaclaw.configuration.ConfigurationChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class JavaClawApplication {

    private static final Logger log = LoggerFactory.getLogger(JavaClawApplication.class);
    private static ConfigurableApplicationContext applicationContext;

    public static void main(String[] args) {
        applicationContext = SpringApplication.run(JavaClawApplication.class, args);
    }

    @Component
    static public class JavaClawApplicationMonitor implements ApplicationRunner {


        private final Environment environment;

        public JavaClawApplicationMonitor(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            String isConfigured = environment.getProperty("agent.onboarding.completed");
            if (Boolean.parseBoolean(isConfigured)) {
                log.info("JavaClaw is running and waiting for your commands!");
            } else {
                log.info("JavaClaw is waiting to be configured! Navigate to http://localhost:{}/onboarding to start the onboarding wizard", environment.getProperty("local.server.port"));
            }
        }

        @EventListener
        public void on(ConfigurationChangedEvent configurationChangedEvent) {
            // Model-provider changes are applied at runtime via ConfigurationRebinder +
            // ChatClientRegistry (hot reload), so they must NOT trigger a full restart.
            boolean providerChangeOnly = !configurationChangedEvent.changedKeys().isEmpty()
                    && configurationChangedEvent.changedKeys().stream()
                    .allMatch(key -> key.equals("agent.llm.providers") || key.startsWith("agent.llm.providers."));
            if (providerChangeOnly) {
                log.info("Configuration change limited to model providers; applying without restart.");
                return;
            }

            ApplicationArguments args = applicationContext.getBean(ApplicationArguments.class);

            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    applicationContext.close();
                    applicationContext = SpringApplication.run(JavaClawApplication.class, args.getSourceArgs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            thread.setDaemon(false);
            thread.start();
        }
    }
}
