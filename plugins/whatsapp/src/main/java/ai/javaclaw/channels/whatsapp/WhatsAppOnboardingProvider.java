package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(55)
public class WhatsAppOnboardingProvider implements OnboardingProvider {

    static final String SESSION_ALLOWED_JID = "onboarding.whatsapp.allowed-chat-jid";

    private static final String CONFIG_ENABLED = "agent.channels.whatsapp.enabled";
    private static final String CONFIG_ALLOWED_JID = "agent.channels.whatsapp.allowed-chat-jid";

    /**
     * A one-to-one chat JID. Groups, newsletters and broadcasts are deliberately not accepted:
     * the assistant authorises by chat, so a chat with more than one other person in it would let
     * any of them command it.
     *
     * <p>Unanchored, so it also picks the JID out of a whole line chosen from the chat list, such
     * as {@code "Alice - 1234567890@s.whatsapp.net"}. The trailing look-ahead stops it matching
     * inside a longer word.
     */
    private static final Pattern JID_PATTERN =
            Pattern.compile("[0-9A-Za-z._-]+@(?:s\\.whatsapp\\.net|lid)(?![0-9A-Za-z._-])");

    private final Environment env;
    private final WhatsApp whatsApp;

    public WhatsAppOnboardingProvider(Environment env, WhatsApp whatsApp) {
        this.env = env;
        this.whatsApp = whatsApp;
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
        boolean installed = whatsApp.isInstalled();
        boolean paired = installed && whatsApp.isPaired();
        model.put("wacliInstalled", installed);
        model.put("wacliPaired", paired);
        model.put("whatsappChats", paired ? whatsApp.oneToOneChats() : List.of());
        model.put("whatsappAllowedChatJid", session.getOrDefault(SESSION_ALLOWED_JID,
                env.getProperty(CONFIG_ALLOWED_JID, "")));
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        if (!whatsApp.isInstalled()) {
            return "wacli is not installed. " + WhatsApp.INSTALL_HINT + " Then try again.";
        }

        String chosen = formParams.getOrDefault("whatsappAllowedChatJid", "").trim();
        if (chosen.isBlank()) {
            return "Choose the WhatsApp chat the assistant should answer in.";
        }
        String jid = extractJid(chosen);
        if (jid == null) {
            return "That is not a one-to-one WhatsApp chat. Pick one from the list, or run "
                    + "'wacli chats list' in your terminal to find its JID. Groups are not supported.";
        }

        session.put(SESSION_ALLOWED_JID, jid);

        if (!whatsApp.isPaired()) {
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
        properties.put(CONFIG_ENABLED, true);
        properties.put(CONFIG_ALLOWED_JID, jid);
        configurationManager.updateProperties(properties);
    }

    /**
     * Pulls the JID out of what the form sent, which is either a plain JID that was typed or
     * pasted, or a whole line picked from the chat list.
     *
     * @return the JID, or {@code null} if there is no one-to-one chat JID in there
     */
    private static String extractJid(String chosen) {
        Matcher matcher = JID_PATTERN.matcher(chosen);
        return matcher.find() ? matcher.group() : null;
    }
}
