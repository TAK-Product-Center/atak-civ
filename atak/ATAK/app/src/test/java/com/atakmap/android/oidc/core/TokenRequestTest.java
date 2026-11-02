package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

public final class TokenRequestTest {

    @Test
    public void formBodyHasFiveUrlEncodedFieldsAndNoClientSecret() {
        String body = TokenRequest.formBody("code +&", "com.atakmap.app.civ:/oidc/callback",
                "atak civ", "verifier~value");
        Map<String, String> fields = RedirectResponse.queryParams("https://local/?" + body);
        assertEquals(5, fields.size());
        assertEquals("authorization_code", fields.get("grant_type"));
        assertEquals("code +&", fields.get("code"));
        assertEquals("com.atakmap.app.civ:/oidc/callback", fields.get("redirect_uri"));
        assertEquals("atak civ", fields.get("client_id"));
        assertEquals("verifier~value", fields.get("code_verifier"));
        assertFalse(body.contains("client_secret"));
    }
}
