package com.atakmap.android.oidc.core;

/**
 * RFC 4648 section 5 base64url, unpadded. Pure Java so the core runs on
 * Android API 21 (java.util.Base64 needs API 26) and on a plain JVM.
 */
final class Base64Url {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    .toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) {
            DECODE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = i;
        }
    }

    private Base64Url() {
    }

    /** Encodes without padding. */
    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int v = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8
                    | (data[i + 2] & 0xff);
            out.append(ALPHABET[v >>> 18 & 63]).append(ALPHABET[v >>> 12 & 63])
                    .append(ALPHABET[v >>> 6 & 63]).append(ALPHABET[v & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int v = (data[i] & 0xff) << 16;
            out.append(ALPHABET[v >>> 18 & 63]).append(ALPHABET[v >>> 12 & 63]);
        } else if (rest == 2) {
            int v = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8;
            out.append(ALPHABET[v >>> 18 & 63]).append(ALPHABET[v >>> 12 & 63])
                    .append(ALPHABET[v >>> 6 & 63]);
        }
        return out.toString();
    }

    /**
     * Strict decode: only the base64url alphabet, optional trailing '=' padding,
     * no whitespace, and a length that is not 1 mod 4.
     *
     * @throws IllegalArgumentException on any malformed input
     */
    static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("null base64url");
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '=') {
            end--;
        }
        int pads = text.length() - end;
        if (pads > 2 || end % 4 == 1 || (pads > 0 && (end + pads) % 4 != 0)) {
            throw new IllegalArgumentException("bad base64url length");
        }
        byte[] out = new byte[end * 3 / 4];
        int o = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            int v = c < 128 ? DECODE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException("bad base64url character");
            }
            buffer = buffer << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (buffer >>> bits & 0xff);
            }
        }
        return out;
    }
}
