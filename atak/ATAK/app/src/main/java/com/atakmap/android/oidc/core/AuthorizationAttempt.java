package com.atakmap.android.oidc.core;

import java.security.SecureRandom;
import java.util.List;

/** In-memory values for one authorization-code attempt. */
public final class AuthorizationAttempt {

    private final String clientId;
    private final String redirectUri;
    private final String state;
    private final String nonce;
    private final String codeVerifier;

    public static AuthorizationAttempt create(String clientId, String redirectUri) {
        return create(clientId, redirectUri, newState());
    }

    /**
     * Builds the attempt around a state generated earlier, so the receiver
     * can be bound to it before it is started (v02, Silas L4).
     */
    public static AuthorizationAttempt create(String clientId, String redirectUri,
            String state) {
        return new AuthorizationAttempt(clientId, redirectUri, state, randomValue(),
                Pkce.newVerifier());
    }

    public static String newState() {
        return randomValue();
    }

    public AuthorizationAttempt(String clientId, String redirectUri, String state,
            String nonce, String codeVerifier) {
        if (clientId == null || redirectUri == null || state == null || nonce == null) {
            throw new IllegalArgumentException("authorization attempt values are required");
        }
        if (!Pkce.isValidVerifier(codeVerifier)) {
            throw new IllegalArgumentException("invalid code_verifier");
        }
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.state = state;
        this.nonce = nonce;
        this.codeVerifier = codeVerifier;
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64Url.encode(bytes);
    }

    public String getClientId() {
        return clientId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getState() {
        return state;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getCodeChallenge() {
        return Pkce.challengeS256(codeVerifier);
    }

    public String buildAuthorizationUrl(String authorizationEndpoint, List<String> scopes) {
        StringBuilder parameters = new StringBuilder();
        append(parameters, "response_type", "code");
        append(parameters, "client_id", clientId);
        append(parameters, "redirect_uri", redirectUri);
        append(parameters, "scope", joinScopes(scopes));
        append(parameters, "state", state);
        append(parameters, "nonce", nonce);
        append(parameters, "code_challenge", getCodeChallenge());
        append(parameters, "code_challenge_method", "S256");

        int fragmentIndex = authorizationEndpoint.indexOf('#');
        String fragment = fragmentIndex >= 0
                ? authorizationEndpoint.substring(fragmentIndex) : "";
        String endpoint = fragmentIndex >= 0
                ? authorizationEndpoint.substring(0, fragmentIndex) : authorizationEndpoint;
        String separator;
        if (endpoint.indexOf('?') < 0) {
            separator = "?";
        } else if (endpoint.endsWith("?") || endpoint.endsWith("&")) {
            separator = "";
        } else {
            separator = "&";
        }
        return endpoint + separator + parameters + fragment;
    }

    private static void append(StringBuilder result, String name, String value) {
        if (result.length() > 0) {
            result.append('&');
        }
        result.append(UrlCodec.encode(name));
        result.append('=');
        result.append(UrlCodec.encode(value));
    }

    private static String joinScopes(List<String> scopes) {
        StringBuilder result = new StringBuilder();
        if (scopes != null) {
            for (String scope : scopes) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(scope);
            }
        }
        return result.toString();
    }

    @Override
    public String toString() {
        return "AuthorizationAttempt[redacted]";
    }
}
