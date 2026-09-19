package com.atakmap.android.oidc.core;

/** One-shot in-memory handoff from the Android redirect activity. */
public final class SchemeRedirectRendezvous implements RedirectReceiver {

    public static final String REDIRECT_URI = "com.atakmap.app.civ:/oidc/callback";

    private static final Object MAILBOX_LOCK = new Object();

    /*
     * The sole mutable static state in the core package. It is an in-memory,
     * one-shot mailbox for the one scheme redirect attempt allowed at a time.
     */
    private static SchemeRedirectRendezvous waiting;

    private boolean started;
    private boolean cancelled;
    private String deliveredUri;

    public static boolean deliver(String redirectUri) {
        if (redirectUri == null) {
            return false;
        }
        synchronized (MAILBOX_LOCK) {
            if (waiting == null || !waiting.started || waiting.cancelled
                    || waiting.deliveredUri != null) {
                return false;
            }
            waiting.deliveredUri = redirectUri;
            waiting.started = false;
            waiting = null;
            MAILBOX_LOCK.notifyAll();
            return true;
        }
    }

    @Override
    public void start() throws OidcException {
        synchronized (MAILBOX_LOCK) {
            if (waiting != null && waiting != this) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "another redirect attempt is active");
            }
            deliveredUri = null;
            cancelled = false;
            started = true;
            waiting = this;
        }
    }

    @Override
    public String getRedirectUri() {
        return REDIRECT_URI;
    }

    @Override
    public String awaitRedirect(long timeoutMillis) throws OidcException {
        long remaining = Math.max(0, timeoutMillis);
        long startedAt = System.nanoTime();
        synchronized (MAILBOX_LOCK) {
            while (deliveredUri == null && !cancelled && remaining > 0) {
                try {
                    MAILBOX_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    clearWaiting();
                    throw browserCancelled();
                }
                long elapsedNanos = System.nanoTime() - startedAt;
                long elapsedMillis = elapsedNanos / 1000000L;
                remaining = Math.max(0, timeoutMillis - elapsedMillis);
            }
            if (deliveredUri != null) {
                return deliveredUri;
            }
            clearWaiting();
            throw browserCancelled();
        }
    }

    @Override
    public void cancel() {
        synchronized (MAILBOX_LOCK) {
            cancelled = true;
            clearWaiting();
            MAILBOX_LOCK.notifyAll();
        }
    }

    @Override
    public void close() {
        cancel();
    }

    private void clearWaiting() {
        started = false;
        if (waiting == this) {
            waiting = null;
        }
    }

    private static OidcException browserCancelled() {
        return new OidcException(OidcError.BROWSER_CANCELLED,
                "redirect cancelled or timed out");
    }
}
