package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class RedirectResponseTest {

    @Test
    public void goodSchemeRedirectYieldsCode() throws Exception {
        assertEquals("good-code", RedirectResponse.extractCode(
                "com.atakmap.app.civ:/oidc/callback?code=good-code&state=expected",
                "expected"));
    }

    @Test
    public void goodLoopbackRedirectYieldsCode() throws Exception {
        assertEquals("good-code", RedirectResponse.extractCode(
                "http://127.0.0.1:49152/oidc/callback?code=good-code&state=expected",
                "expected"));
    }

    @Test
    public void percentEncodedValuesAreDecoded() throws Exception {
        assertEquals("code with symbols/+", RedirectResponse.extractCode(
                "com.atakmap.app.civ:/oidc/callback?code=code%20with%20symbols%2F%2B"
                        + "&state=state%20value",
                "state value"));
    }

    @Test
    public void stateMismatchIsTokenInvalidWithExactSafeMessage() {
        OidcException error = expectFailure(
                "com.atakmap.app.civ:/oidc/callback?code=secret-code&state=wrong",
                "expected");
        assertEquals(OidcError.TOKEN_INVALID, error.getError());
        assertEquals("state mismatch", error.getMessage());
        assertFalse(error.getMessage().contains("secret-code"));
    }

    @Test
    public void missingStateIsRejectedWithoutLeakingCode() {
        OidcException error = expectFailure(
                "com.atakmap.app.civ:/oidc/callback?code=secret-code", "expected");
        assertEquals(OidcError.TOKEN_INVALID, error.getError());
        assertEquals("state mismatch", error.getMessage());
        assertFalse(error.getMessage().contains("secret-code"));
    }

    @Test
    public void accessDeniedMapsToBrowserCancelled() {
        OidcException error = expectFailure(
                "com.atakmap.app.civ:/oidc/callback?error=access_denied&state=expected",
                "expected");
        assertEquals(OidcError.BROWSER_CANCELLED, error.getError());
    }

    @Test
    public void serverErrorMapsToTokenInvalidAndIgnoresDescription() {
        OidcException error = expectFailure(
                "com.atakmap.app.civ:/oidc/callback?error=server_error&state=expected"
                        + "&error_description=secret-code-should-not-appear",
                "expected");
        assertEquals(OidcError.TOKEN_INVALID, error.getError());
        assertEquals("authorization error: server_error", error.getMessage());
        assertFalse(error.getMessage().contains("secret-code"));
    }

    @Test
    public void missingCodeMapsToTokenInvalid() {
        OidcException error = expectFailure(
                "com.atakmap.app.civ:/oidc/callback?state=expected", "expected");
        assertEquals(OidcError.TOKEN_INVALID, error.getError());
    }

    @Test
    public void exceptionMessagesNeverContainAuthorizationCode() {
        String code = "never-print-this-code";
        OidcException error = expectFailure(
                "http://127.0.0.1:49152/oidc/callback?code=" + code + "&state=bad",
                "expected");
        assertFalse(error.getMessage().contains(code));
    }

    private static OidcException expectFailure(String uri, String state) {
        try {
            RedirectResponse.extractCode(uri, state);
        } catch (OidcException expected) {
            return expected;
        }
        throw new AssertionError("redirect was accepted");
    }
}
