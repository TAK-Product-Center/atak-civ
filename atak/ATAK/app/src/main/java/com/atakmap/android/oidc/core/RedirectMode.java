package com.atakmap.android.oidc.core;

/** Supported native-app authorization redirect modes. */
public enum RedirectMode {
    SCHEME,
    LOOPBACK;

    public static RedirectMode fromWellKnown(String value) {
        return "loopback".equals(value) ? LOOPBACK : SCHEME;
    }
}
