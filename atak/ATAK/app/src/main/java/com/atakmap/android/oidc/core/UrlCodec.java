package com.atakmap.android.oidc.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Small UTF-8 URL codec with no Android dependency. */
final class UrlCodec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlCodec() {
    }

    static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte valueByte : bytes) {
            int b = valueByte & 0xff;
            if (isUnreserved(b)) {
                encoded.append((char) b);
            } else {
                encoded.append('%');
                encoded.append(HEX[b >>> 4]);
                encoded.append(HEX[b & 0x0f]);
            }
        }
        return encoded.toString();
    }

    static String formEncode(String value) {
        return encode(value).replace("%20", "+");
    }

    static String decode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '%') {
                bytes.reset();
                while (index < value.length() && value.charAt(index) == '%') {
                    if (index + 2 >= value.length()) {
                        throw new IllegalArgumentException("invalid percent encoding");
                    }
                    int high = hex(value.charAt(index + 1));
                    int low = hex(value.charAt(index + 2));
                    if (high < 0 || low < 0) {
                        throw new IllegalArgumentException("invalid percent encoding");
                    }
                    bytes.write((high << 4) | low);
                    index += 3;
                }
                decoded.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } else {
                decoded.append(c == '+' ? ' ' : c);
                index++;
            }
        }
        return decoded.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isUnreserved(int b) {
        return b >= 'A' && b <= 'Z'
                || b >= 'a' && b <= 'z'
                || b >= '0' && b <= '9'
                || b == '-' || b == '.' || b == '_' || b == '~';
    }
}
