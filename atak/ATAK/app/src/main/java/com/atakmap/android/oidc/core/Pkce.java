package com.atakmap.android.oidc.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Proof Key for Code Exchange, RFC 7636, S256 only. */
public final class Pkce {

    private Pkce() {
    }

    public static String newVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challengeS256(String verifier) {
        if (!isValidVerifier(verifier)) {
            throw new IllegalArgumentException(
                    "code_verifier must be 43-128 unreserved characters");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean isValidVerifier(String verifier) {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) {
            return false;
        }
        for (int i = 0; i < verifier.length(); i++) {
            char c = verifier.charAt(i);
            if (!isUnreserved(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnreserved(char c) {
        return c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9'
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
