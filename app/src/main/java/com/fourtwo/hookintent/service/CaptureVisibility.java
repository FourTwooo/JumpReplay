package com.fourtwo.hookintent.service;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import com.fourtwo.hookintent.data.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** URI grants establish Android's documented provider-to-recipient package visibility. */
public final class CaptureVisibility extends BroadcastReceiver {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static boolean started;
    public static synchronized void start(Context context) {
        if(Build.VERSION.SDK_INT<30 || started) return;
        started=true;
        Context app=context.getApplicationContext();
        IntentFilter packages=new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);packages.addDataScheme("package");
        CaptureVisibility receiver=new CaptureVisibility();
        if(Build.VERSION.SDK_INT>=33) app.registerReceiver(receiver,packages,Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver,packages);
        IO.execute(() -> grantAll(app));
    }
    @Override public void onReceive(Context context,Intent intent) {
        if(Build.VERSION.SDK_INT<30) return;
        PendingResult pending=goAsync();
        Context app=context.getApplicationContext();
        IO.execute(() -> {
            try {
                if(intent.getData()!=null) grant(app,intent.getData().getSchemeSpecificPart());
                else grantAll(app);
            } finally { if(pending!=null) pending.finish(); }
        });
    }
    private static void grantAll(Context context) {
        try {
            for(ApplicationInfo app:context.getPackageManager().getInstalledApplications(0))
                if(app.uid!=android.os.Process.myUid() && app.uid%100000>=10000) grant(context,app.packageName);
        } catch(Exception error) { Log.w("CaptureVisibility","Cannot enumerate recipients",error); }
    }
    private static void grant(Context context,String target) {
        if(target==null || context.getPackageName().equals(target)) return;
        try {
            // The provider is already exported. This grants no write access and does not
            // change the identity checks of the separate parameter-editing endpoint.
            context.grantUriPermission(target,Constants.CONFIG_URI,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(Exception error) { Log.w("CaptureVisibility","Cannot establish visibility for "+target,error); }
    }
}
