package com.fakejira.auth.oauth;

/**
 * One sign-in provider's endpoints and credentials. Enabled when a client id and secret are configured.
 */
public record OAuthProvider(String id, String label, String clientId, String clientSecret, String authorizeUrl,
                            String tokenUrl, String userUrl, String emailsUrl, String scope) {

    public boolean enabled() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
