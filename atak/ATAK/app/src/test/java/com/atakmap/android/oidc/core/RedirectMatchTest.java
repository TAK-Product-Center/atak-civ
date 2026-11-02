package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v02 (Silas L4, Astra rank 8): the shared acceptance rule for both receivers. */
public final class RedirectMatchTest {

    private static final String URI = SchemeRedirectRendezvous.REDIRECT_URI
            + "?code=abc&state=expected";

    @Test
    public void unboundStateMatchesNothing() {
        assertFalse(RedirectMatch.stateMatches(URI, null));
        assertFalse(RedirectMatch.stateMatches(
                SchemeRedirectRendezvous.REDIRECT_URI + "?code=abc", null));
        assertFalse(RedirectMatch.stateMatches(null, null));
    }

    @Test
    public void boundStateMatchesOnlyTheExactValue() {
        assertTrue(RedirectMatch.stateMatches(URI, "expected"));
        assertFalse(RedirectMatch.stateMatches(URI, "expecte"));
        assertFalse(RedirectMatch.stateMatches(URI, "expected2"));
        assertFalse(RedirectMatch.stateMatches(URI, ""));
        assertFalse(RedirectMatch.stateMatches(
                SchemeRedirectRendezvous.REDIRECT_URI + "?code=abc", "expected"));
        assertFalse(RedirectMatch.stateMatches(null, "expected"));
    }
}
