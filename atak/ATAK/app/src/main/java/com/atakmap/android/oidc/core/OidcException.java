package com.atakmap.android.oidc.core;

/** An OIDC failure with a safe, user-facing category and detail. */
public final class OidcException extends Exception {

    private static final long serialVersionUID = 1L;

    private final OidcError error;

    public OidcException(OidcError error, String safeMessage) {
        super(safeMessage);
        this.error = error;
    }

    public OidcException(OidcError error, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.error = error;
    }

    public OidcError getError() {
        return error;
    }
}
