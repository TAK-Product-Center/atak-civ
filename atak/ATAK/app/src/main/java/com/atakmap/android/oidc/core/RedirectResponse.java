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
                    || "user_cancelled".equals(error) || "user_canceled".equals(error)
                    || "user_cancel".equals(error)
                    || "cancelled".equals(error)) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "authorization cancelled");
            }
            throw new OidcException(OidcError.TOKEN_INVALID,
                    "authorization error: " + safeErrorCode(error));
        }

        String code = values.get("code");
        if (code == null || code.isEmpty()) {
            throw new OidcException(OidcError.TOKEN_INVALID,
                    "authorization code missing");
        }
        return code;
    }

    private static String safeErrorCode(String error) {
        StringBuilder safe = new StringBuilder();
        int limit = Math.min(error.length(), 80);
        for (int i = 0; i < limit; i++) {
            char c = error.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '_' || c == '-') {
                safe.append(c);
            } else {
                safe.append('?');
            }
        }
        return safe.toString();
    }
}
