package com.fourtwo.hookintent.replay;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Permissionless UID: application-defined Parcelable/Serializable code never inherits host privileges. */
public final class ExtrasSandboxService extends Service {
    private ExtrasRuntime runtime;
    @Override public void onCreate() { super.onCreate(); runtime = new ExtrasRuntime(this, null, true); }
    @Override public IBinder onBind(Intent intent) { return runtime.binder(); }
    @Override public void onDestroy() { runtime.dispose(); super.onDestroy(); }
}
