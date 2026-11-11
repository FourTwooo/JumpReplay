package com.fourtwo.hookintent.utils;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

/** Keeps the fields which an editable URI cannot represent (arrays, ClipData, etc.). */
public final class IntentReplay {
    private IntentReplay() { }

    public static Intent fromText(String text, Intent original, String originalText) throws URISyntaxException {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Intent 不能为空");
        if (original == null) return Intent.parseUri(text, 0);
        Intent result = new Intent(original);
        if (text.equals(originalText)) return result;
        if (!text.contains("#Intent;")) {
            result.setDataAndType(Uri.parse(text), original.getType());
            return result;
        }
        Intent parsed = Intent.parseUri(text, 0);
        result.setAction(parsed.getAction());
        result.setDataAndType(parsed.getData(), parsed.getType());
        result.setComponent(parsed.getComponent());
        result.setSelector(null);
        result.setPackage(parsed.getPackage());
        result.setSelector(parsed.getSelector());
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        result.setFlags((parsed.getFlags() & ~grants) | (original.getFlags() & grants));
        Set<String> oldCategories = result.getCategories();
        if (oldCategories != null) {
            for (String category : new HashSet<>(oldCategories)) result.removeCategory(category);
        }
        if (parsed.getCategories() != null) {
            for (String category : parsed.getCategories()) result.addCategory(category);
        }
        // Only remove URI-representable extras. An int[] omitted by toUri must survive editing.
        Bundle previous = Intent.parseUri(originalText == null ? original.toUri(Intent.URI_INTENT_SCHEME)
                : originalText, 0).getExtras();
        if (previous != null) for (String key : previous.keySet()) result.removeExtra(key);
        Bundle changed = parsed.getExtras();
        if (changed != null) result.putExtras(changed);
        return result;
    }
}
