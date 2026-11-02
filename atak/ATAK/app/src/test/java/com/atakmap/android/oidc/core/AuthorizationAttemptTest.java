package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.junit.Test;

public final class AuthorizationAttemptTest {

    private static final String VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @Test
    public void schemeAuthorizationUrlHasRequiredEncodedFieldsAndNoClientSecret() {
        AuthorizationAttempt attempt = new AuthorizationAttempt("atak civ",
                SchemeRedirectRendezvous.REDIRECT_URI, "state value", "nonce value", VERIFIER);
        String url = attempt.buildAuthorizationUrl("https://idp.local/auth",
                Arrays.asList("openid", "profile", "groups"));
        Map<String, String> values = RedirectResponse.queryParams(url);

        assertEquals("code", values.get("response_type"));
        assertEquals("atak civ", values.get("client_id"));
        assertEquals(SchemeRedirectRendezvous.REDIRECT_URI, values.get("redirect_uri"));
        assertEquals("openid profile groups", values.get("scope"));
        assertEquals("state value", values.get("state"));
        assertEquals("nonce value", values.get("nonce"));
        assertEquals(Pkce.challengeS256(VERIFIER), values.get("code_challenge"));
        assertEquals("S256", values.get("code_challenge_method"));
        assertFalse(url.contains("client_secret"));
        assertTrue(url.contains("redirect_uri=com.atakmap.app.civ%3A%2Foidc%2Fcallback"));
        assertTrue(url.contains("scope=openid%20profile%20groups"));
    }

    @Test
    public void loopbackRedirectUriIsPercentEncoded() {
        AuthorizationAttempt attempt = new AuthorizationAttempt("atak-civ",
                "http://127.0.0.1:49152/oidc/callback", "state", "nonce", VERIFIER);
        String url = attempt.buildAuthorizationUrl("https://idp.local/auth",
                Arrays.asList("openid"));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A49152%2Foidc%2Fcallback"));
        assertEquals("http://127.0.0.1:49152/oidc/callback",
                RedirectResponse.queryParams(url).get("redirect_uri"));
    }

    @Test
    public void authorizationEndpointExistingQueryIsKept() {
        AuthorizationAttempt attempt = new AuthorizationAttempt("atak-civ",
                SchemeRedirectRendezvous.REDIRECT_URI, "state", "nonce", VERIFIER);
        String url = attempt.buildAuthorizationUrl(
                "https://idp.local/auth?prompt=login", Arrays.asList("openid"));
        assertTrue(url.startsWith("https://idp.local/auth?prompt=login&response_type=code"));
        assertEquals("login", RedirectResponse.queryParams(url).get("prompt"));
    }

    @Test
    public void toStringContainsNoneOfVerifierStateOrNonce() {
        AuthorizationAttempt attempt = new AuthorizationAttempt("client", "redirect",
                "state-secret", "nonce-secret", VERIFIER);
        String rendered = attempt.toString();
        assertEquals("AuthorizationAttempt[redacted]", rendered);
        assertFalse(rendered.contains(VERIFIER));
        assertFalse(rendered.contains("state-secret"));
        assertFalse(rendered.contains("nonce-secret"));
    }

    @Test
    public void createUsesDistinct32ByteBase64UrlStateAndNonce() {
        AuthorizationAttempt attempt = AuthorizationAttempt.create("client", "redirect");
        byte[] state = Base64.getUrlDecoder().decode(attempt.getState());
        byte[] nonce = Base64.getUrlDecoder().decode(attempt.getNonce());
        assertEquals(43, attempt.getState().length());
        assertEquals(43, attempt.getNonce().length());
        assertEquals(32, state.length);
        assertEquals(32, nonce.length);
        assertNotEquals(attempt.getState(), attempt.getNonce());
        assertFalse(Arrays.equals(state, nonce));
        assertArrayEquals(state, Base64.getUrlDecoder().decode(attempt.getState()));
    }
}
