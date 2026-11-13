package com.fourtwo.hookintent.xposed;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public final class ActivityCapturePolicy {
    private ActivityCapturePolicy() { }

    /** Preserve the old three sender switches using the actual calling chain. */
    public static boolean senderAllowed(StackTraceElement[] stack, BiPredicate<String, String> enabled) {
        boolean hasLegacyEntry = false;
        for (StackTraceElement frame : stack) {
            String owner = frame.getClassName(), method = frame.getMethodName();
            if (("android.app.Activity".equals(owner)
                    && ("startActivity".equals(method) || "startActivityForResult".equals(method)))
                    || ("android.content.ContextWrapper".equals(owner) && "startActivity".equals(method))) {
                hasLegacyEntry = true;
                if (enabled.test(owner, method)) return true;
            }
        }
        return !hasLegacyEntry;
    }

    /** Only direct Intent/Intent[] parameters are launch arguments; never unpack extras. */
    public static List<Intent> sendingIntents(Object[] args) {
        ArrayList<Intent> intents = new ArrayList<>();
        if (args != null) for (Object arg : args) {
            if (arg instanceof Intent) intents.add((Intent) arg);
            else if (arg instanceof Intent[]) for (Intent intent : (Intent[]) arg)
                if (intent != null) intents.add(intent);
        }
        return intents;
    }
}
