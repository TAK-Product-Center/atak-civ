package com.atakmap.android.oidc.core;

/** Receives one native-app authorization redirect. */
public interface RedirectReceiver {

    void start() throws OidcException;

    String getRedirectUri();

    /**
     * Binds the state value this attempt expects. Once bound, a redirect whose
     * state does not match is ignored (T12: a stray or hostile callback must
     * not consume the attempt). Call before the browser is launched.
     */
    void bindState(String expectedState);

    String awaitRedirect(long timeoutMillis) throws OidcException;

    void cancel();

    void close();
}
