package com.atakmap.net;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds OIDC bearers behind opaque, one-shot handles so a bearer is never
 * written into a Parcel or Intent. DeviceProfileRequest travels through
 * DataDroid as a Parcelable.
 */
public final class OidcBearerVault {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ConcurrentHashMap<String, Entry> BEARERS =
            new ConcurrentHashMap<>();

    /**
     * F04: a bearer that nobody took (dispatch failure, missing callback) must
     * not live forever. Every put and take evicts expired entries first.
     */
    static final long TTL_MILLIS = 2L * 60L * 1000L;

    private static final class Entry {
        final String assertion;
        final long expiresAt;

        Entry(String assertion, long expiresAt) {
            this.assertion = assertion;
            this.expiresAt = expiresAt;
        }
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> e : BEARERS.entrySet()) {
            if (e.getValue().expiresAt <= now) {
                BEARERS.remove(e.getKey(), e.getValue());
            }
        }
    }

    private OidcBearerVault() {
    }

    public static String put(String assertion) {
        if (assertion == null) {
            throw new IllegalArgumentException("assertion is required");
        }

        evictExpired();
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        while (true) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            char[] chars = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                chars[i * 2] = HEX[value >>> 4];
                chars[i * 2 + 1] = HEX[value & 0x0f];
            }

            String handle = new String(chars);
            if (BEARERS.putIfAbsent(handle, new Entry(assertion, expiresAt)) == null) {
                return handle;
            }
        }
    }

    public static String take(String handle) {
        evictExpired();
        if (handle == null) {
            return null;
        }
        Entry entry = BEARERS.remove(handle);
        return entry == null ? null : entry.assertion;
    }

    public static void clear() {
        BEARERS.clear();
    }
}
