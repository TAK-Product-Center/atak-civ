package com.atakmap.android.oidc.core;

/** Receives one native-app authorization redirect. */
public interface RedirectReceiver {

    void start() throws OidcException;

    String getRedirectUri();

    String awaitRedirect(long timeoutMillis) throws OidcException;

    void cancel();

    void close();
}
