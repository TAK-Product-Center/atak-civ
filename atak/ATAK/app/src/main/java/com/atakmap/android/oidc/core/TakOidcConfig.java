package com.atakmap.android.oidc.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The ATAK fields returned by TAK Server's OIDC discovery document. */
public final class TakOidcConfig {

    private static final List<String> DEFAULT_SCOPES = Collections.unmodifiableList(
            Arrays.asList("openid", "profile", "groups"));

    private final String issuer;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String androidClientId;
    private final List<String> scopes;
    private final RedirectMode redirectMode;

    private TakOidcConfig(String issuer, String authorizationEndpoint, String tokenEndpoint,
            String androidClientId, List<String> scopes, RedirectMode redirectMode) {
        this.issuer = issuer;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.androidClientId = androidClientId;
        this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
        this.redirectMode = redirectMode;
    }

    public static TakOidcConfig parse(String json) throws OidcException {
        final Map<String, Object> object;
        try {
            object = MiniJson.parseObject(json);
        } catch (OidcException e) {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    "invalid discovery document", e);
        }

        String issuer = requireString(object, "issuer");
        String authorizationEndpoint = requireString(object, "authorization_endpoint");
        String tokenEndpoint = requireString(object, "token_endpoint");
        String androidClientId = requireString(object, "android_client_id");

        requireHttps("issuer", issuer);
        requireHttps("authorization_endpoint", authorizationEndpoint);
        requireHttps("token_endpoint", tokenEndpoint);

        List<String> scopes = parseScopes(object.get("scopes"));
        Object redirect = object.get("android_redirect");
        String redirectValue = redirect instanceof String ? (String) redirect : null;
        return new TakOidcConfig(issuer, authorizationEndpoint, tokenEndpoint,
                androidClientId, scopes, RedirectMode.fromWellKnown(redirectValue));
    }

    private static String requireString(Map<String, Object> object, String field)
            throws OidcException {
        Object value = object.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    field + " is required");
        }
        return (String) value;
    }

    private static void requireHttps(String field, String value) throws OidcException {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new OidcException(OidcError.DISCOVERY_FAILED,
                        field + " must use https");
            }
        } catch (URISyntaxException e) {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    field + " must use https", e);
        }
    }

    private static List<String> parseScopes(Object value) throws OidcException {
        if (value == null) {
            return DEFAULT_SCOPES;
        }
        if (!(value instanceof List<?>)) {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    "scopes must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object scope : (List<?>) value) {
            if (!(scope instanceof String) || ((String) scope).isEmpty()) {
                throw new OidcException(OidcError.DISCOVERY_FAILED,
                        "scopes must contain strings");
            }
            result.add((String) scope);
        }
        return result;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public String getAndroidClientId() {
        return androidClientId;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public RedirectMode getRedirectMode() {
        return redirectMode;
    }
}
