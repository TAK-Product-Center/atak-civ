package com.atakmap.android.oidc.core;

/** Selects the signed assertion sent to TAK Server. */
public final class BearerSelector {

    private BearerSelector() {
    }

    public static String select(TokenResponse response) throws OidcException {
        if (response != null && Jwt.isJwt(response.getAccessToken())) {
            return response.getAccessToken();
        }
        if (response != null && Jwt.isJwt(response.getIdToken())) {
            return response.getIdToken();
        }
        throw new OidcException(OidcError.TOKEN_INVALID,
                "no JWT bearer assertion");
    }
}
