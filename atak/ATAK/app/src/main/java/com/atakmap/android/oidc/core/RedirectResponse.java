package com.atakmap.android.oidc.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses and validates an authorization redirect without exposing its code. */
public final class RedirectResponse {

    private RedirectResponse() {
    }

    public static Map<String, String> queryParams(String uri) {
        if (uri == null) {
            return Collections.emptyMap();
        }
        final String query;
        try {
            query = new URI(uri).getRawQuery();
        } catch (URISyntaxException e) {
            return Collections.emptyMap();
        }
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        String[] fields = query.split("&", -1);
        try {
            for (String field : fields) {
                int separator = field.indexOf('=');
                String rawName = separator >= 0 ? field.substring(0, separator) : field;
                String rawValue = separator >= 0 ? field.substring(separator + 1) : "";
                String name = UrlCodec.decode(rawName);
                if (!result.containsKey(name)) {
                    result.put(name, UrlCodec.decode(rawValue));
                }
            }
        } catch (IllegalArgumentException e) {
            return Collections.emptyMap();
        }
        return result;
    }

    public static String extractCode(String redirectUri, String expectedState)
            throws OidcException {
        Map<String, String> values = queryParams(redirectUri);
        if (!ConstantTime.equals(expectedState, values.get("state"))) {
            throw new OidcException(OidcError.TOKEN_INVALID, "state mismatch");
        }

        String error = values.get("error");
        if (error != null) {
            if ("access_denied".equals(error) || "login_required".equals(error)
                    || "interaction_required".equals(error)
                    || "consent_required".equals(error)) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "authorization cancelled");
            }
            // Fixed vocabulary only: the raw error value is never copied into a
            // message, so a value that echoes state or code cannot leak through it.
            throw new OidcException(OidcError.TOKEN_INVALID,
                    "authorization error: " + errorCategory(error));
        }

        String code = values.get("code");
        if (code == null || code.isEmpty()) {
            throw new OidcException(OidcError.TOKEN_INVALID,
                    "authorization code missing");
        }
        return code;
    }

    private static final String[] KNOWN_ERRORS = {
            "invalid_request", "unauthorized_client", "unsupported_response_type",
            "invalid_scope", "server_error", "temporarily_unavailable",
            "invalid_request_uri", "invalid_request_object", "request_not_supported",
            "request_uri_not_supported", "registration_not_supported",
            "account_selection_required" };

    static String errorCategory(String error) {
        for (String known : KNOWN_ERRORS) {
            if (known.equals(error)) {
                return known;
            }
        }
        return "unknown";
    }
}
