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
        RedirectMode redirectMode = parseRedirectMode(object.get("android_redirect"));
        return new TakOidcConfig(issuer, authorizationEndpoint, tokenEndpoint,
                androidClientId, scopes, redirectMode);
    }

    /**
     * Absent means scheme (the pre-v0.2 server never sent the field). Any
     * present value must be exactly "scheme" or "loopback": a typo in the
     * server configuration must not silently pick a redirect the IdP has not
     * registered.
     */
    private static RedirectMode parseRedirectMode(Object value) throws OidcException {
        if (value == null) {
            return RedirectMode.SCHEME;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("scheme".equals(text)) {
                return RedirectMode.SCHEME;
            }
            if ("loopback".equals(text)) {
                return RedirectMode.LOOPBACK;
            }
        }
        throw new OidcException(OidcError.DISCOVERY_FAILED,
                "android_redirect must be scheme or loopback");
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

    /**
     * The server has emitted scopes both as a JSON array and as one space
     * delimited string (review round v02, D2). Both shapes normalise to the
     * same list; an empty list, blank string or any other type is rejected.
     */
    static List<String> parseScopes(Object value) throws OidcException {
        if (value == null) {
            return DEFAULT_SCOPES;
        }
        List<String> result = new ArrayList<>();
        if (value instanceof String) {
            for (String scope : ((String) value).trim().split("\\s+")) {
                if (!scope.isEmpty()) {
                    result.add(scope);
                }
            }
        } else if (value instanceof List<?>) {
            for (Object scope : (List<?>) value) {
                if (!(scope instanceof String) || ((String) scope).trim().isEmpty()) {
                    throw new OidcException(OidcError.DISCOVERY_FAILED,
                            "scopes must contain strings");
                }
                result.add(((String) scope).trim());
            }
        } else {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    "scopes must be an array or a space delimited string");
        }
        if (result.isEmpty()) {
            throw new OidcException(OidcError.DISCOVERY_FAILED,
                    "scopes must not be empty");
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
