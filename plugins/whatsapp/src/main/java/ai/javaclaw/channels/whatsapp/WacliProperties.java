package ai.javaclaw.channels.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.channels.whatsapp")
public class WacliProperties {

    private boolean enabled;

    private String wacliPath = "wacli";

    private String allowedChatJid;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWacliPath() {
        return wacliPath;
    }

    public void setWacliPath(String wacliPath) {
        this.wacliPath = wacliPath;
    }

    public String getAllowedChatJid() {
        return allowedChatJid;
    }

    public void setAllowedChatJid(String allowedChatJid) {
        this.allowedChatJid = allowedChatJid;
    }
}
