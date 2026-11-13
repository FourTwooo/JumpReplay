package com.fourtwo.hookintent.xposed;

import android.content.Intent;
import java.lang.reflect.Method;
import java.util.List;

/** Only known AOSP query signatures are allowed to modify a system-server result. */
public final class SystemQuerySpec {
    private SystemQuerySpec() { }

    public static String[] owners() {
        return new String[]{"com.android.server.pm.ComputerEngine",
                "com.android.server.pm.PackageManagerService$ComputerEngine",
                "com.android.server.pm.PackageManagerService"};
    }

    public static int userIdIndex(Method method) {
        if (!"queryIntentActivitiesInternal".equals(method.getName())
                || !List.class.isAssignableFrom(method.getReturnType())) return -1;
        Class<?>[] p = method.getParameterTypes();
        if (p.length < 4 || p[0] != Intent.class || p[1] != String.class
                || (p[2] != int.class && p[2] != long.class)) return -1;
        if (p.length == 4 && p[3] == int.class) return 3;
        if (p.length != 7 && p.length != 8 && p.length != 9) return -1;
        if (p.length == 7 && p[2] != int.class) return -1;
        if (p[3] != p[2] || p[p.length - 2] != boolean.class
                || p[p.length - 1] != boolean.class) return -1;
        for (int i = 4; i < p.length - 2; i++) if (p[i] != int.class) return -1;
        return p.length - 3;
    }

    public static Method select(Class<?> owner) {
        Method selected = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (userIdIndex(method) < 0) continue;
            if (selected == null || method.getParameterTypes().length > selected.getParameterTypes().length)
                selected = method;
        }
        return selected;
    }

    public static Intent implicitUri(Intent original) {
        if (original == null || original.getComponent() != null || original.getPackage() != null) return null;
        Intent intent = original.getSelector() == null ? original : original.getSelector();
        return intent.getComponent() == null && intent.getPackage() == null
                && intent.getScheme() != null && intent.getDataString() != null ? intent : null;
    }
}
