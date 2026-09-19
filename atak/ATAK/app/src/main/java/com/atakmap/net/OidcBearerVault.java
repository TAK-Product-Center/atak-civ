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
    private static final ConcurrentHashMap<String, String> BEARERS =
            new ConcurrentHashMap<>();

    private OidcBearerVault() {
    }

    public static String put(String assertion) {
        if (assertion == null) {
            throw new IllegalArgumentException("assertion is required");
        }

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
            if (BEARERS.putIfAbsent(handle, assertion) == null) {
                return handle;
            }
        }
    }

    public static String take(String handle) {
        if (handle == null) {
            return null;
        }
        return BEARERS.remove(handle);
    }

    public static void clear() {
        BEARERS.clear();
    }
}
