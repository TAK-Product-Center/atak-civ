package com.atakmap.android.oidc;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import com.atakmap.android.oidc.core.OidcError;
import com.atakmap.android.oidc.core.OidcException;

import java.util.List;

final class CustomTabLauncher {
    private static final String CUSTOM_TABS_SERVICE_ACTION =
            "android.support.customtabs.action.CustomTabsService";
    private static final String EXTRA_SESSION =
            "android.support.customtabs.extra.SESSION";
    private static final String EXTRA_TITLE_VISIBILITY =
            "android.support.customtabs.extra.TITLE_VISIBILITY";

    private CustomTabLauncher() {
    }

    static void launch(Context context, String url) throws OidcException {
        PackageManager packageManager = context.getPackageManager();
        Intent serviceIntent = new Intent(CUSTOM_TABS_SERVICE_ACTION);
        List<ResolveInfo> providers = packageManager.queryIntentServices(
                serviceIntent, 0);

        String provider = chooseProvider(packageManager, providers);
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (provider != null) {
            Bundle extras = new Bundle();
            extras.putBinder(EXTRA_SESSION, null);
            browserIntent.putExtras(extras);
            browserIntent.putExtra(EXTRA_TITLE_VISIBILITY, 1);
            browserIntent.setPackage(provider);
        }

        try {
            context.startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            throw new OidcException(OidcError.BROWSER_CANCELLED,
                    "no browser available");
        }
    }

    /**
     * F03 (T13): a Custom Tab is opened only inside the browser the user has
     * chosen as default for https links, and only when that browser offers
     * the Custom Tabs service. Any other package that merely declares the
     * service is never selected; without a qualifying default browser the
     * plain ACTION_VIEW intent goes through the normal system resolution.
     */
    private static String chooseProvider(PackageManager packageManager,
            List<ResolveInfo> providers) {
        if (providers == null || providers.isEmpty())
            return null;

        Intent viewIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://example.com"));
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo defaultBrowser = packageManager.resolveActivity(viewIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (defaultBrowser == null || defaultBrowser.activityInfo == null)
            return null;
        String defaultPackage = defaultBrowser.activityInfo.packageName;
        if (defaultPackage == null || "android".equals(defaultPackage))
            return null;

        for (ResolveInfo provider : providers) {
            if (provider == null || provider.serviceInfo == null)
                continue;
            if (defaultPackage.equals(provider.serviceInfo.packageName))
                return defaultPackage;
        }
        return null;
    }
}
