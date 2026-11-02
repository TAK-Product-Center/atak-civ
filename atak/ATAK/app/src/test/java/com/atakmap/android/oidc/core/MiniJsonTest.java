package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class MiniJsonTest {

    @Test
    public void parsesNestedObjectsArraysEscapesUnicodeNumbersBooleansAndNull()
            throws Exception {
        String json = "{\"nested\":{\"array\":[1,-2.5,true,false,null,"
                + "\"line\\nquote\\\"slash\\\\unicode\\u00e9literalé\"]}}";
        Map<String, Object> root = MiniJson.parseObject(json);
        Map<?, ?> nested = (Map<?, ?>) root.get("nested");
        List<?> array = (List<?>) nested.get("array");
        assertEquals(Long.valueOf(1), array.get(0));
        assertEquals(Double.valueOf(-2.5), array.get(1));
        assertEquals(Boolean.TRUE, array.get(2));
        assertEquals(Boolean.FALSE, array.get(3));
        assertNull(array.get(4));
        assertEquals("line\nquote\"slash\\unicodeéliteralé", array.get(5));
    }

    @Test
    public void trailingGarbageIsRefused() {
        assertInvalid("{\"ok\":true} trailing");
    }

    @Test
    public void unterminatedStringIsRefused() {
        assertInvalid("{\"value\":\"unterminated}");
    }

    private static void assertInvalid(String json) {
        try {
            MiniJson.parse(json);
        } catch (OidcException expected) {
            assertEquals(OidcError.DISCOVERY_FAILED, expected.getError());
            return;
        }
        throw new AssertionError("invalid JSON was accepted");
    }
}
