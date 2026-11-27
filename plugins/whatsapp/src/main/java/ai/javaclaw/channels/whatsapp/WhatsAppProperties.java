package ai.javaclaw.channels.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.channels.whatsapp")
public class WhatsAppProperties {

    private boolean enabled;

    private String allowedChatJid;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAllowedChatJid() {
        return allowedChatJid;
    }

    public void setAllowedChatJid(String allowedChatJid) {
        this.allowedChatJid = allowedChatJid;
    }
}
