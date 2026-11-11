package com.fourtwo.hookintent.utils;

import com.fourtwo.hookintent.ui.blocker.DeepLinkRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** URI constraints belong to an entire intent-filter, not to individual data tags. */
final class ManifestFilterData {
    private final Set<String> schemes = new LinkedHashSet<>();
    private final Map<String, String[]> authorities = new LinkedHashMap<>();
    private final Map<String, String[]> paths = new LinkedHashMap<>();

    void add(String scheme, String host, String port,
             String path, String prefix, String pattern) {
        if (!scheme.isEmpty()) {
            schemes.add(scheme);
        }
        // Android associates a port with the host on the same data element.
        if (!host.isEmpty()) {
            authorities.put(host + "\u0000" + port, new String[]{host, port});
        }
        addPath(path, "", "");
        addPath("", prefix, "");
        addPath("", "", pattern);
    }

    private void addPath(String path, String prefix, String pattern) {
        if (!path.isEmpty() || !prefix.isEmpty() || !pattern.isEmpty()) {
            paths.put(path + "\u0000" + prefix + "\u0000" + pattern,
                    new String[]{path, prefix, pattern});
        }
    }

    List<DeepLinkRule> toRules(String packageName, String label, String activityName) {
        List<DeepLinkRule> result = new ArrayList<>();
        for (String scheme : schemes) {
            if (authorities.isEmpty()) {
                // Without an authority, Android ignores any path constraints.
                result.add(rule(packageName, label, activityName, scheme,
                        new String[]{"", ""}, new String[]{"", "", ""}));
                continue;
            }
            for (String[] authority : authorities.values()) {
                if (paths.isEmpty()) {
                    result.add(rule(packageName, label, activityName, scheme,
                            authority, new String[]{"", "", ""}));
                } else {
                    for (String[] path : paths.values()) {
                        result.add(rule(packageName, label, activityName, scheme, authority, path));
                    }
                }
            }
        }
        return result;
    }

    private DeepLinkRule rule(String packageName, String label, String activityName,
                              String scheme, String[] authority, String[] path) {
        return new DeepLinkRule(packageName, label, activityName, scheme,
                authority[0], authority[1], path[0], path[1], path[2]);
    }
}
