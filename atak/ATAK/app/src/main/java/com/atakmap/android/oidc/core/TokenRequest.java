package com.atakmap.android.oidc.core;

/** Builds the public-client authorization-code token request body. */
public final class TokenRequest {

    private TokenRequest() {
    }

    public static String formBody(String code, String redirectUri, String clientId,
            String codeVerifier) {
        StringBuilder body = new StringBuilder();
        append(body, "grant_type", "authorization_code");
        append(body, "code", code);
        append(body, "redirect_uri", redirectUri);
        append(body, "client_id", clientId);
        append(body, "code_verifier", codeVerifier);
        return body.toString();
    }

    private static void append(StringBuilder body, String name, String value) {
        if (body.length() > 0) {
            body.append('&');
        }
        body.append(UrlCodec.formEncode(name));
        body.append('=');
        body.append(UrlCodec.formEncode(value));
    }
}
