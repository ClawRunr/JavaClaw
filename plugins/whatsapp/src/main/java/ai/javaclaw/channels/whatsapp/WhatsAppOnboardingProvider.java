package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Order(56)
public class WhatsAppOnboardingProvider implements OnboardingProvider {

    static final String SESSION_ALLOWED_JID = "onboarding.whatsapp.allowed-chat-jid";

    private static final String ENABLED_PROPERTY = "agent.channels.whatsapp.enabled";
    private static final String ALLOWED_JID_PROPERTY = "agent.channels.whatsapp.allowed-chat-jid";

    private static final Pattern JID_PATTERN = Pattern.compile("^[0-9A-Za-z._-]+@(s\\.whatsapp\\.net|g\\.us|lid|newsletter|broadcast)$");

    private final Environment env;
    private final WacliCli wacliCli;

    @Autowired
    public WhatsAppOnboardingProvider(Environment env, CliRunner cliRunner) {
        this(env, new DefaultWacliCli(cliRunner, env.getProperty("agent.channels.whatsapp.wacli-path", "wacli")));

    }

    WhatsAppOnboardingProvider(Environment env, WacliCli wacliCli) {
        this.env = env;
        this.wacliCli = wacliCli;
    }

    @Override
    public boolean isOptional() {return true;}

    @Override
    public String getStepId() {return "whatsapp";}

    @Override
    public String getStepTitle() {return "WhatsApp";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/whatsapp";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        boolean installed = wacliCli.isInstalled();
        model.put("wacliInstalled", installed);
        model.put("wacliPaired", installed && wacliCli.isPaired());
        model.put("whatsappAllowedChatJid", session.getOrDefault(SESSION_ALLOWED_JID,
                env.getProperty(ALLOWED_JID_PROPERTY, "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        if (!wacliCli.isInstalled()) {
            return "wacli is not installed. Install it (macOS: 'brew install openclaw/tap/wacli', "
                    + "Linux: 'go install github.com/openclaw/wacli@latest') and try again.";
        }

        String jid = normalizeJid(formParams.get("whatsappAllowedChatJid"));
        if (jid == null) {
            return "Enter the WhatsApp chat JID in the format 1234567890@s.whatsapp.net.";
        }
        if (!JID_PATTERN.matcher(jid).matches()) {
            return "That doesn't look like a valid WhatsApp chat JID. Use the format 1234567890@s.whatsapp.net.";
        }

        session.put(SESSION_ALLOWED_JID, jid);

        if (!wacliCli.isPaired()) {
            return "wacli is not paired yet. Run 'wacli auth' in your terminal, scan the QR code with "
                    + "WhatsApp on your phone (Linked devices), then click Continue.";
        }

        return null;
    }

    @Override
    public void saveConfiguration(Map<String, Object> session, ConfigurationManager configurationManager) throws IOException {
        String jid = (String) session.get(SESSION_ALLOWED_JID);
        if (jid == null) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ENABLED_PROPERTY, true);
        properties.put(ALLOWED_JID_PROPERTY, jid);
        configurationManager.updateProperties(properties);
    }

    private static String normalizeJid(String jid) {
        if (jid == null) {
            return null;
        }
        String normalized = jid.trim();
        return normalized.isBlank() ? null : normalized;
    }

    interface WacliCli {
        boolean isInstalled();

        boolean isPaired();
    }

    static class DefaultWacliCli implements WacliCli {

        private final CliRunner cliRunner;
        private final String wacliPath;

        DefaultWacliCli(CliRunner cliRunner, String wacliPath) {
            this.cliRunner = cliRunner;
            this.wacliPath = wacliPath;
        }

        @Override
        public boolean isInstalled() {
            return runQuietly(List.of(wacliPath, "version"));
        }

        @Override
        public boolean isPaired() {
            return runQuietly(Arrays.asList(wacliPath, "auth", "status", "--json"));
        }

        private boolean runQuietly(List<String> command) {
            try {
                CliResult result = cliRunner.run(command);
                return result.exitCode() == 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
