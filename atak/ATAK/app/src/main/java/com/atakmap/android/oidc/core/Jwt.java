package com.atakmap.android.oidc.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Minimal JWT shape and unverified-claim helpers. */
public final class Jwt {

    private Jwt() {
    }

    public static boolean isJwt(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String[] segments = value.split("\\.", -1);
        if (segments.length != 3) {
            return false;
        }
        for (String segment : segments) {
            if (!isBase64UrlSegment(segment)) {
                return false;
            }
            try {
                Base64.getUrlDecoder().decode(segment);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBase64UrlSegment(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, Object> unverifiedClaims(String jwt) throws OidcException {
        if (!isJwt(jwt)) {
            throw invalidToken("token is not a JWT", null);
        }
        try {
            String[] segments = jwt.split("\\.", -1);
            byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
            return MiniJson.parseObject(new String(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | OidcException e) {
            throw invalidToken("JWT claims are invalid", e);
        }
    }

    public static void checkNonce(String idToken, String expectedNonce) throws OidcException {
        if (idToken == null || expectedNonce == null) {
            throw invalidToken("nonce missing", null);
        }
        Object nonce = unverifiedClaims(idToken).get("nonce");
        if (!(nonce instanceof String)
                || !ConstantTime.equals(expectedNonce, (String) nonce)) {
            throw invalidToken("nonce mismatch", null);
        }
    }

    private static OidcException invalidToken(String message, Throwable cause) {
        if (cause == null) {
            return new OidcException(OidcError.TOKEN_INVALID, message);
        }
        return new OidcException(OidcError.TOKEN_INVALID, message, cause);
    }
}
