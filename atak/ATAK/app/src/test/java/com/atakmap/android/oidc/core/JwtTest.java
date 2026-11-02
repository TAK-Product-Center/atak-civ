package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.Test;

public final class JwtTest {

    @Test
    public void isJwtAcceptsExactlyThreeBase64UrlSegments() {
        assertTrue(Jwt.isJwt("abc.def.ghi"));
    }

    @Test
    public void isJwtRejectsOpaqueTwoFourEmptyNullPaddingAndNonBase64UrlValues() {
        assertFalse(Jwt.isJwt("opaque-token"));
        assertFalse(Jwt.isJwt("abc.def"));
        assertFalse(Jwt.isJwt("abc.def.ghi.jkl"));
        assertFalse(Jwt.isJwt(""));
        assertFalse(Jwt.isJwt(null));
        assertFalse(Jwt.isJwt("abc=.def.ghi"));
        assertFalse(Jwt.isJwt("abc.def*.ghi"));
        assertFalse(Jwt.isJwt("abc..ghi"));
    }

    @Test
    public void unverifiedClaimsDecodesHandBuiltHeaderPayloadSignature() throws Exception {
        String token = jwt("{\"alg\":\"RS256\"}",
                "{\"sub\":\"alice\",\"nonce\":\"n-1\",\"admin\":false}");
        Map<String, Object> claims = Jwt.unverifiedClaims(token);
        assertEquals("alice", claims.get("sub"));
        assertEquals("n-1", claims.get("nonce"));
        assertEquals(Boolean.FALSE, claims.get("admin"));
    }

    @Test
    public void checkNonceAcceptsExactMatch() throws Exception {
        Jwt.checkNonce(jwt("{}", "{\"nonce\":\"expected\"}"), "expected");
    }

    @Test
    public void checkNonceRejectsMismatchWithTokenInvalid() {
        assertTokenInvalid(jwt("{}", "{\"nonce\":\"actual\"}"), "expected");
    }

    @Test
    public void checkNonceRejectsMissingNonceWithTokenInvalid() {
        assertTokenInvalid(jwt("{}", "{\"sub\":\"alice\"}"), "expected");
    }

    @Test
    public void checkNonceRejectsNonJwtWithTokenInvalid() {
        assertTokenInvalid("opaque", "expected");
    }

    @Test
    public void checkNonceRejectsNullWithTokenInvalid() {
        assertTokenInvalid(null, "expected");
    }

    private static void assertTokenInvalid(String token, String nonce) {
        try {
            Jwt.checkNonce(token, nonce);
        } catch (OidcException expected) {
            assertEquals(OidcError.TOKEN_INVALID, expected.getError());
            return;
        }
        throw new AssertionError("invalid nonce was accepted");
    }

    static String jwt(String header, String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString("signature".getBytes(StandardCharsets.UTF_8));
    }
}
