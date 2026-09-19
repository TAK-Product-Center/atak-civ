package com.atakmap.android.oidc.core;

/** Shared redirect acceptance rule for both receivers. */
final class RedirectMatch {

    private RedirectMatch() {
    }

    /**
     * True when the redirect carries the bound state (or no state is bound
     * yet). Uses a constant time comparison. Never returns the code.
     */
    static boolean stateMatches(String redirectUri, String expectedState) {
        if (expectedState == null) {
            return true;
        }
        String state = RedirectResponse.queryParams(redirectUri).get("state");
        return ConstantTime.equals(expectedState, state);
    }
}
