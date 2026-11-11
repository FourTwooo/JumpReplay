package com.fourtwo.blockertemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The same jump may visit each generated blocker at most once. */
final class ForwardingHistory {
    private ForwardingHistory() {
    }

    static ArrayList<String> withVisit(Collection<String> previous, String packageName) {
        Set<String> visited = new LinkedHashSet<>();
        if (previous != null) {
            for (String item : previous) {
                if (item != null && !item.isEmpty()) {
                    visited.add(item);
                }
            }
        }
        visited.add(packageName);
        return new ArrayList<>(visited);
    }

    static boolean shouldExclude(boolean blockerEntry, String packageName, List<String> visited) {
        return blockerEntry && visited != null && visited.contains(packageName);
    }
}
