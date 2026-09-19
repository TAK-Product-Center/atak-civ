package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class PkceTest {

    @Test
    public void rfc7636AppendixBVectorProducesExpectedS256Challenge() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challengeS256(verifier));
    }

    @Test
    public void newVerifierIs43CharactersValidAndUniqueAcrossCalls() {
        String first = Pkce.newVerifier();
        String second = Pkce.newVerifier();
        assertEquals(43, first.length());
        assertEquals(43, second.length());
        assertTrue(Pkce.isValidVerifier(first));
        assertTrue(Pkce.isValidVerifier(second));
        assertNotEquals(first, second);
    }

    @Test
    public void plainPkceMethodDoesNotExist() {
        for (Method method : Pkce.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("plain"));
        }
    }

    @Test
    public void invalidVerifierIsRejected() {
        assertFalse(Pkce.isValidVerifier(null));
        assertFalse(Pkce.isValidVerifier("short"));
        assertFalse(Pkce.isValidVerifier(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r/wW1gFWFOEjXk"));
        try {
            Pkce.challengeS256("short");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid verifier was accepted");
    }
}
