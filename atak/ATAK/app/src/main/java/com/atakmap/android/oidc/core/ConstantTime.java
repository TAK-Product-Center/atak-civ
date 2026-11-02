package com.atakmap.android.oidc.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Constant-time comparison for short protocol values. */
final class ConstantTime {

    private ConstantTime() {
    }

    static boolean equals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }
}
