package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class TokenResponseTest {

    @Test
    public void parseReadsAccessIdAndTokenType() throws Exception {
        TokenResponse response = TokenResponse.parse(
                "{\"access_token\":\"access-value\","
                        + "\"id_token\":\"id-value\",\"token_type\":\"Bearer\"}");
        assertEquals("access-value", response.getAccessToken());
        assertEquals("id-value", response.getIdToken());
        assertEquals("Bearer", response.getTokenType());
    }

    @Test
    public void missingAccessTokenIsTokenInvalid() {
        try {
            TokenResponse.parse("{\"id_token\":\"id-value\"}");
        } catch (OidcException expected) {
            assertEquals(OidcError.TOKEN_INVALID, expected.getError());
            return;
        }
        throw new AssertionError("missing access_token was accepted");
    }

    @Test
    public void toStringRedactsAllTokenValues() throws Exception {
        TokenResponse response = TokenResponse.parse(
                "{\"access_token\":\"access-secret\",\"id_token\":\"id-secret\"}");
        assertEquals("TokenResponse[redacted]", response.toString());
        assertFalse(response.toString().contains("access-secret"));
        assertFalse(response.toString().contains("id-secret"));
    }
}
