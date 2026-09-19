package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class TakOidcConfigTest {

    private static final String FULL_DOCUMENT = "{"
            + "\"issuer\":\"https://kc.local:8443/realms/tak\","
            + "\"authorization_endpoint\":\"https://kc.local:8443/realms/tak/protocol/openid-connect/auth\","
            + "\"token_endpoint\":\"https://kc.local:8443/realms/tak/protocol/openid-connect/token\","
            + "\"android_client_id\":\"atak-civ\","
            + "\"scopes\":[\"openid\",\"profile\",\"groups\"],"
            + "\"android_redirect\":\"loopback\"}";

    @Test
    public void fullDiscoveryDocumentParsesAllFields() throws Exception {
        TakOidcConfig config = TakOidcConfig.parse(FULL_DOCUMENT);
        assertEquals("https://kc.local:8443/realms/tak", config.getIssuer());
        assertEquals("https://kc.local:8443/realms/tak/protocol/openid-connect/auth",
                config.getAuthorizationEndpoint());
        assertEquals("https://kc.local:8443/realms/tak/protocol/openid-connect/token",
                config.getTokenEndpoint());
        assertEquals("atak-civ", config.getAndroidClientId());
        assertEquals(Arrays.asList("openid", "profile", "groups"), config.getScopes());
        assertEquals(RedirectMode.LOOPBACK, config.getRedirectMode());
    }

    @Test
    public void scopesDefaultToOpenidProfileGroupsWhenAbsent() throws Exception {
        TakOidcConfig config = TakOidcConfig.parse(document(null, null));
        assertEquals(Arrays.asList("openid", "profile", "groups"), config.getScopes());
    }

    @Test
    public void androidRedirectLoopbackSchemeAbsentAndGarbageMapAsRequired() throws Exception {
        assertEquals(RedirectMode.LOOPBACK,
                TakOidcConfig.parse(document(null, "loopback")).getRedirectMode());
        assertEquals(RedirectMode.SCHEME,
                TakOidcConfig.parse(document(null, "scheme")).getRedirectMode());
        assertEquals(RedirectMode.SCHEME,
                TakOidcConfig.parse(document(null, null)).getRedirectMode());
        assertEquals(RedirectMode.SCHEME,
                TakOidcConfig.parse(document(null, "garbage")).getRedirectMode());
    }

    @Test
    public void t17RejectsHttpIssuerAndEachEndpointNamingTheField() {
        assertHttpFieldRejected("issuer");
        assertHttpFieldRejected("authorization_endpoint");
        assertHttpFieldRejected("token_endpoint");
    }

    @Test
    public void missingAndroidClientIdIsDiscoveryFailedAndNamesField() {
        String json = "{"
                + "\"issuer\":\"https://idp.local\","
                + "\"authorization_endpoint\":\"https://idp.local/auth\","
                + "\"token_endpoint\":\"https://idp.local/token\"}";
        assertDiscoveryFailure(json, "android_client_id");
    }

    @Test
    public void nonJsonDiscoveryDocumentIsRefused() {
        assertDiscoveryFailure("not JSON", "discovery");
    }

    private static void assertHttpFieldRejected(String field) {
        String json = documentWithUrls(
                "issuer".equals(field) ? "http://idp.local" : "https://idp.local",
                "authorization_endpoint".equals(field)
                        ? "http://idp.local/auth" : "https://idp.local/auth",
                "token_endpoint".equals(field)
                        ? "http://idp.local/token" : "https://idp.local/token");
        assertDiscoveryFailure(json, field);
    }

    private static void assertDiscoveryFailure(String json, String messagePart) {
        try {
            TakOidcConfig.parse(json);
        } catch (OidcException expected) {
            assertEquals(OidcError.DISCOVERY_FAILED, expected.getError());
            assertTrue(expected.getMessage().contains(messagePart));
            return;
        }
        throw new AssertionError("invalid discovery document was accepted");
    }

    private static String document(String scopes, String redirect) {
        StringBuilder json = new StringBuilder(documentWithUrls(
                "https://idp.local", "https://idp.local/auth", "https://idp.local/token"));
        json.deleteCharAt(json.length() - 1);
        if (scopes != null) {
            json.append(",\"scopes\":").append(scopes);
        }
        if (redirect != null) {
            json.append(",\"android_redirect\":\"").append(redirect).append('"');
        }
        return json.append('}').toString();
    }

    private static String documentWithUrls(String issuer, String authorization, String token) {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + authorization + "\","
                + "\"token_endpoint\":\"" + token + "\","
                + "\"android_client_id\":\"atak-civ\"}";
    }
}
