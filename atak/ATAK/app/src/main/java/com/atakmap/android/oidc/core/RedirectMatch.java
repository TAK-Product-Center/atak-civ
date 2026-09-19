package com.atakmap.android.oidc.core;

/** Shared redirect acceptance rule for both receivers. */
final class RedirectMatch {

    private RedirectMatch() {
    }

    /**
     * True only when a state is bound and the redirect carries exactly that
     * state (v02, Silas L4 / Astra rank 8: an unbound receiver accepts
     * nothing). Uses a constant time comparison. Never returns the code.
     */
    static boolean stateMatches(String redirectUri, String expectedState) {
        if (expectedState == null) {
            return false;
        }
        String state = RedirectResponse.queryParams(redirectUri).get("state");
        return ConstantTime.equals(expectedState, state);
    }
}
