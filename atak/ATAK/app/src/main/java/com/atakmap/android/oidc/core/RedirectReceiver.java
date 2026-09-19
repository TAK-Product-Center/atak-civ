package com.atakmap.android.oidc.core;

/** Receives one native-app authorization redirect. */
public interface RedirectReceiver {

    void start() throws OidcException;

    String getRedirectUri();

    /**
     * Binds the state value this attempt expects. A redirect whose state does
     * not match is ignored, and so is every redirect while nothing is bound
     * (T12: a stray or hostile callback must not consume the attempt). Call
     * before {@link #start()}: the state survives start, so the receiver is
     * never published unbound.
     */
    void bindState(String expectedState);

    String awaitRedirect(long timeoutMillis) throws OidcException;

    void cancel();

    void close();
}
