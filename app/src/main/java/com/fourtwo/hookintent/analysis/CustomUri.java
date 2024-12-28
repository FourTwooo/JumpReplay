package com.fourtwo.hookintent.analysis;

import java.util.Objects;

public class CustomUri {

    private final static int NOT_CALCULATED = -2;
    private final static int NOT_FOUND = -1;

    private final String uriString;
    private volatile int cachedSsi = NOT_CALCULATED;
    private String scheme = NotCachedHolder.NOT_CACHED;

    static class NotCachedHolder {
        private NotCachedHolder() {
            // prevent instantiation
        }
        static final String NOT_CACHED = "NOT CACHED";
    }

    private CustomUri(String uriString) {
        if (uriString == null) {
            throw new NullPointerException("uriString");
        }
        this.uriString = uriString;
    }

    /** Finds the first ':' or '#Intent;'. Returns -1 if none found. */
    private int findSchemeSeparator() {
        if (cachedSsi == NOT_CALCULATED) {
            if (uriString.startsWith("#Intent;")) {
                cachedSsi = 7; // Position after "#Intent;"
            } else {
                cachedSsi = uriString.indexOf(':');
            }
        }
        return cachedSsi;
    }

    public String getScheme() {
        boolean cached = (!Objects.equals(scheme, NotCachedHolder.NOT_CACHED));
        return cached ? scheme : (scheme = parseScheme());
    }

    private String parseScheme() {
        int ssi = findSchemeSeparator();
        if (ssi == NOT_FOUND) {
            return null;
        } else if (uriString.startsWith("#Intent;")) {
            return "Intent";
        } else {
            return uriString.substring(0, ssi);
        }
    }

    // Static method to get scheme
    public static String getScheme(String uriString) {
        return new CustomUri(uriString).getScheme();
    }

    public static void main(String[] args) {
        String schemeUrl = "#Intent;launchFlags=0x1000c000;component=com.oneplus.note/.ui.EditActivity;end";
        System.out.println("Scheme: " + CustomUri.getScheme(schemeUrl)); // Output: Scheme: Intent

        String normalUrl = "http://example.com";
        System.out.println("Scheme: " + CustomUri.getScheme(normalUrl)); // Output: Scheme: http
    }
}
