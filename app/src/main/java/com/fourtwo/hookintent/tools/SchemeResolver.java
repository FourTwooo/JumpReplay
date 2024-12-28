package com.fourtwo.hookintent.tools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.List;

public class SchemeResolver {

    /**
     * Finds the package name of the application that can handle a given URI.
     *
     * @param context The application context.
     * @param uriString The complete URI to look for (e.g., "dewulink://m.dewu.com/note").
     * @return The package name that can handle the given URI, or null if none is found.
     */
    public static String findAppByUri(Context context, String uriString) {
        PackageManager packageManager = context.getPackageManager();

        // Create an Intent with the specified URI
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(uriString));

        // Query for activities that can handle the intent
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        if (resolveInfoList != null && !resolveInfoList.isEmpty()) {
            // Return the package name of the first application found
            String packageName = resolveInfoList.get(0).activityInfo.packageName;
            Log.d("SchemeResolver", "Package supporting URI " + uriString + ": " + packageName);
            return packageName;
        } else {
            Log.d("SchemeResolver", "No applications found for URI: " + uriString);
            return "";
        }
    }
}