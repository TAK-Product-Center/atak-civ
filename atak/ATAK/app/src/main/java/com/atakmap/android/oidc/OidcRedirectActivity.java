package com.atakmap.android.oidc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.atakmap.android.oidc.core.SchemeRedirectRendezvous;
import com.atakmap.app.ATAKActivity;

public class OidcRedirectActivity extends Activity {
    private static final String REDIRECT_SCHEME = "com.atakmap.app.civ";
    private static final String REDIRECT_PATH = "/oidc/callback";
    /**
     * F-24: set on the ATAKActivity intent when a well formed redirect
     * arrived but no attempt was waiting for it (the process was killed while
     * the browser was in the foreground, or the attempt had already timed
     * out). ATAKActivity turns it into the sign-in cancelled dialog with a
     * restart hint instead of silence.
     */
    public static final String EXTRA_REDIRECT_EXPIRED =
            "com.atakmap.android.oidc.REDIRECT_EXPIRED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleRedirect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRedirect();
    }

    private void handleRedirect() {
        Intent intent = getIntent();
        Uri uri = intent == null ? null : intent.getData();
        // Exactly scheme:/oidc/callback?query: no authority, no fragment.
        if (uri == null || !REDIRECT_SCHEME.equals(uri.getScheme())
                || !REDIRECT_PATH.equals(uri.getPath())
                || uri.getEncodedAuthority() != null
                || uri.getEncodedFragment() != null) {
            finish();
            return;
        }

        boolean delivered = SchemeRedirectRendezvous.deliver(uri.toString());
        if (!delivered && SchemeRedirectRendezvous.isWaiting()) {
            // An attempt is waiting and this delivery did not match it
            // (T12): ignore it and leave the attempt waiting.
            finish();
            return;
        }

        Intent atakIntent = new Intent(this, ATAKActivity.class);
        atakIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!delivered) {
            atakIntent.putExtra(EXTRA_REDIRECT_EXPIRED, true);
        }
        startActivity(atakIntent);
        finish();
    }
}
