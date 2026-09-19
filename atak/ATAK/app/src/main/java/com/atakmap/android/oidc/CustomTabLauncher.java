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

    private static String chooseProvider(PackageManager packageManager,
            List<ResolveInfo> providers) {
        if (providers == null || providers.isEmpty())
            return null;

        Intent viewIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://example.com"));
        ResolveInfo defaultBrowser = packageManager.resolveActivity(viewIntent,
                0);
        String defaultPackage = defaultBrowser != null
                && defaultBrowser.activityInfo != null
                        ? defaultBrowser.activityInfo.packageName
                        : null;

        String firstProvider = null;
        for (ResolveInfo provider : providers) {
            if (provider == null || provider.serviceInfo == null)
                continue;
            String packageName = provider.serviceInfo.packageName;
            if (packageName == null)
                continue;
            if (firstProvider == null)
                firstProvider = packageName;
            if (packageName.equals(defaultPackage))
                return packageName;
        }
        return firstProvider;
    }
}
