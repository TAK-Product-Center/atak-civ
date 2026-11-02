package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

/** F10: the core must not depend on java.util.Base64 (Android API 26). */
public final class Base64UrlTest {

    @Test
    public void rfc4648VectorsEncodeUnpadded() {
        assertEquals("", Base64Url.encode(new byte[0]));
        assertEquals("Zg", Base64Url.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm8", Base64Url.encode("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9v", Base64Url.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("-_8", Base64Url.encode(new byte[] {(byte) 0xfb, (byte) 0xff}));
    }

    @Test
    public void roundTripsRandomInputAgainstJavaUtilBase64() {
        Random random = new Random(7);
        for (int len = 0; len < 70; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            String encoded = Base64Url.encode(data);
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data),
                    encoded);
            assertArrayEquals(data, Base64Url.decode(encoded));
            assertArrayEquals(data, Base64Url.decode(
                    java.util.Base64.getUrlEncoder().encodeToString(data)));
        }
    }

    @Test
    public void strictDecodeRejectsMalformedInput() {
        String[] bad = {"Z", "Zm9v=", "Zm 9v", "Zm+v", "Zm/v", "Zm9v===", null};
        for (String input : bad) {
            try {
                Base64Url.decode(input);
                fail("accepted " + input);
            } catch (IllegalArgumentException expected) {
                // strict
            }
        }
    }
}
