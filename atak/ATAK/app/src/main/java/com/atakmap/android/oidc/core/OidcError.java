package com.atakmap.android.oidc.core;

/** Stable error categories exposed by the ATAK OIDC flow. */
public enum OidcError {
    DISCOVERY_FAILED,
    IDP_UNREACHABLE,
    BROWSER_CANCELLED,
    TOKEN_INVALID,
    GROUP_DENIED,
    TLS_FAILURE,
    SERVER_REJECTED
}
