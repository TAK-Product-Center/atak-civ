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
    public void androidRedirectLoopbackSchemeAndAbsentMapAsRequired() throws Exception {
        assertEquals(RedirectMode.LOOPBACK,
                TakOidcConfig.parse(document(null, "loopback")).getRedirectMode());
        assertEquals(RedirectMode.SCHEME,
                TakOidcConfig.parse(document(null, "scheme")).getRedirectMode());
        assertEquals(RedirectMode.SCHEME,
                TakOidcConfig.parse(document(null, null)).getRedirectMode());
    }

    /** v02 D2: an unknown redirect value is a configuration error, not a silent scheme fallback. */
    @Test
    public void unknownAndroidRedirectIsDiscoveryFailedNamingTheField() {
        assertDiscoveryFailure(document(null, "garbage"), "android_redirect");
        assertDiscoveryFailure(document(null, ""), "android_redirect");
        assertDiscoveryFailure(documentRaw(null, "\"android_redirect\":7"), "android_redirect");
    }

    /** v02 D2: the server emits scopes as one space delimited string; both shapes normalise. */
    @Test
    public void scopesAcceptedAsArrayOrSpaceDelimitedStringAndNormaliseToTheSameList()
            throws Exception {
        assertEquals(Arrays.asList("openid", "profile", "groups"),
                TakOidcConfig.parse(document("[\"openid\",\"profile\",\"groups\"]", null))
                        .getScopes());
        assertEquals(Arrays.asList("openid", "profile", "groups"),
                TakOidcConfig.parse(document("\"openid profile groups\"", null)).getScopes());
        assertEquals(Arrays.asList("openid", "email"),
                TakOidcConfig.parse(document("\"  openid   email \"", null)).getScopes());
        assertEquals(Arrays.asList("openid", "email"),
                TakOidcConfig.parse(document("[\" openid\",\"email \"]", null)).getScopes());
    }

    /** v02 D2: an empty or wrongly typed scopes field is rejected, never sent as an empty scope. */
    @Test
    public void emptyOrWrongTypedScopesAreDiscoveryFailed() {
        assertDiscoveryFailure(document("[]", null), "scopes");
        assertDiscoveryFailure(document("\"\"", null), "scopes");
        assertDiscoveryFailure(document("\"   \"", null), "scopes");
        assertDiscoveryFailure(document("[\"openid\",\"\"]", null), "scopes");
        assertDiscoveryFailure(document("[\"openid\",3]", null), "scopes");
        assertDiscoveryFailure(document("42", null), "scopes");
        assertDiscoveryFailure(document("{\"a\":\"openid\"}", null), "scopes");
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

    private static String documentRaw(String scopes, String rawRedirectField) {
        StringBuilder json = new StringBuilder(documentWithUrls(
                "https://idp.local", "https://idp.local/auth", "https://idp.local/token"));
        json.deleteCharAt(json.length() - 1);
        if (scopes != null) {
            json.append(",\"scopes\":").append(scopes);
        }
        if (rawRedirectField != null) {
            json.append(',').append(rawRedirectField);
        }
        return json.append('}').toString();
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
