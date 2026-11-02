package com.atakmap.android.oidc.core;

/** Factory for redirect receivers selected by discovery. */
public final class RedirectReceivers {

    private RedirectReceivers() {
    }

    public static RedirectReceiver forMode(RedirectMode mode) {
        if (mode == RedirectMode.LOOPBACK) {
            return new LoopbackRedirectReceiver();
        }
        return new SchemeRedirectRendezvous();
    }
}
