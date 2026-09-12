package ai.javaclaw.channels.teams;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.channels.teams")
public class TeamsProperties {

    private boolean enabled;
    private String appId;
    private String appSecret;
    private String tenantId;

    /**
     * Optional: Azure AD object ID of the single Teams user the assistant should respond to.
     * When blank, the first user who messages the bot is accepted (not recommended for
     * anything but local testing).
     */
    private String allowedUserId;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAllowedUserId() { return allowedUserId; }
    public void setAllowedUserId(String allowedUserId) { this.allowedUserId = allowedUserId; }
}