package com.fourtwo.blockertemplate;

import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;

final class ForwardingIntents {
    static final String EXTRA_VISITED_BLOCKERS = "com.fourtwo.hookintent.extra.VISITED_BLOCKERS";
    static final String META_DATA_BLOCKER_ENTRY = "com.fourtwo.hookintent.BLOCKER_ENTRY";

    private ForwardingIntents() {
    }

    static ArrayList<String> readHistory(Intent intent) {
        if (intent != null) {
            try {
                ArrayList<?> history = intent.getStringArrayListExtra(EXTRA_VISITED_BLOCKERS);
                if (history != null) {
                    ArrayList<String> packages = new ArrayList<>();
                    for (Object item : history) {
                        if (item instanceof String && !((String) item).isEmpty()) {
                            packages.add((String) item);
                        }
                    }
                    return packages;
                }
            } catch (RuntimeException ignored) {
                // Ignore a malformed optional extra from an external caller.
            }
        }
        return new ArrayList<>();
    }

    static boolean isBlockerEntry(ResolveInfo info) {
        return info.activityInfo != null
                && ((info.activityInfo.metaData != null
                && info.activityInfo.metaData.getBoolean(META_DATA_BLOCKER_ENTRY, false))
                // Recognize older generated blockers without the metadata as well.
                || BlockerEntryActivity.class.getName().equals(info.activityInfo.name));
    }

    static Intent forQuery(Intent original) {
        Intent query = original == null ? new Intent(Intent.ACTION_VIEW) : new Intent(original);
        query.setComponent(null);
        query.setPackage(null);
        query.setSelector(null);
        // Grant flags and task semantics belong to the original jump. In
        // particular, clearing all flags prevents the target from reading files.
        return query;
    }

    static void carryUriGrants(Intent original, Intent destination) {
        int grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        destination.addFlags(original.getFlags() & grantFlags);
        destination.setDataAndType(original.getData(), original.getType());
        destination.setClipData(original.getClipData());
    }

    static Intent forLaunch(Intent base, Intent original, ResolveInfo target, String currentPackage) {
        Intent launch = new Intent(base);
        if (original != null) {
            launch.setFlags(original.getFlags());
            launch.setClipData(original.getClipData());
            launch.replaceExtras(original);
        }
        launch.setClassName(target.activityInfo.packageName, target.activityInfo.name);
        if (isBlockerEntry(target)) {
            launch.putStringArrayListExtra(EXTRA_VISITED_BLOCKERS,
                    ForwardingHistory.withVisit(readHistory(original), currentPackage));
        } else {
            // This is internal routing metadata, not part of the application's payload.
            launch.removeExtra(EXTRA_VISITED_BLOCKERS);
        }
        return launch;
    }
}
