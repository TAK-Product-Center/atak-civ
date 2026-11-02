package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BearerSelectorTest {

    @Test
    public void jwtAccessTokenIsChosen() throws Exception {
        String access = JwtTest.jwt("{}", "{\"kind\":\"access\"}");
        String id = JwtTest.jwt("{}", "{\"kind\":\"id\"}");
        TokenResponse response = TokenResponse.parse(json(access, id));
        assertEquals(access, BearerSelector.select(response));
    }

    @Test
    public void opaqueAccessTokenFallsBackToJwtIdToken() throws Exception {
        String id = JwtTest.jwt("{}", "{\"kind\":\"id\"}");
        TokenResponse response = TokenResponse.parse(json("opaque", id));
        assertEquals(id, BearerSelector.select(response));
    }

    @Test
    public void neitherTokenBeingJwtIsTokenInvalid() throws Exception {
        TokenResponse response = TokenResponse.parse(json("opaque", "also-opaque"));
        try {
            BearerSelector.select(response);
        } catch (OidcException expected) {
            assertEquals(OidcError.TOKEN_INVALID, expected.getError());
            return;
        }
        throw new AssertionError("opaque tokens were accepted");
    }

    private static String json(String access, String id) {
        return "{\"access_token\":\"" + access + "\",\"id_token\":\"" + id
                + "\",\"token_type\":\"Bearer\"}";
    }
}
