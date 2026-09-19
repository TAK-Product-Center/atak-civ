package com.atakmap.android.oidc.core;

import java.util.Map;

/** The token fields needed for nonce validation and TAK enrollment. */
public final class TokenResponse {

    private final String accessToken;
    private final String idToken;
    private final String tokenType;

    private TokenResponse(String accessToken, String idToken, String tokenType) {
        this.accessToken = accessToken;
        this.idToken = idToken;
        this.tokenType = tokenType;
    }

    public static TokenResponse parse(String json) throws OidcException {
        final Map<String, Object> object;
        try {
            object = MiniJson.parseObject(json);
        } catch (OidcException e) {
            throw new OidcException(OidcError.TOKEN_INVALID, "invalid token response", e);
        }
        String accessToken = stringValue(object.get("access_token"));
        if (accessToken == null || accessToken.isEmpty()) {
            throw new OidcException(OidcError.TOKEN_INVALID,
                    "access_token missing");
        }
        return new TokenResponse(accessToken, stringValue(object.get("id_token")),
                stringValue(object.get("token_type")));
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getIdToken() {
        return idToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    @Override
    public String toString() {
        return "TokenResponse[redacted]";
    }
}
