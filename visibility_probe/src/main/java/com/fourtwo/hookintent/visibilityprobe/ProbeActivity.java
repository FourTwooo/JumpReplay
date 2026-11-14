package com.fourtwo.hookintent.visibilityprobe;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.widget.TextView;
import org.json.JSONObject;

/** An ordinary API 33 UID with no package-visibility declarations. */
public final class ProbeActivity extends Activity {
    private final JSONObject result=new JSONObject();
    private TextView text;
    private boolean bound;
    private Messenger remote;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Messenger reply=new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if(message.what==99) {
                put("reply",true);put("reply_data",message.getData().toString());
            } else if(message.what==102) put("registered",true);
        }
    });
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            remote=new Messenger(binder);put("connected",true);
            try {
                Message register=Message.obtain(null,100);register.replyTo=reply;
                Bundle identity=new Bundle();identity.putString("packageName",getPackageName());
                identity.putInt("uid",Process.myUid());register.setData(identity);remote.send(register);
                Message status=Message.obtain(null,1);status.arg1=731;status.replyTo=reply;remote.send(status);
            } catch(Exception error) { put("send_error",error.toString()); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { put("disconnected",true); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);text=new TextView(this);text.setTextSize(14);text.setPadding(24,24,24,24);setContentView(text);
        put("uid",Process.myUid());put("sdk",Build.VERSION.SDK_INT);
        try { getPackageManager().getPackageInfo("com.fourtwo.hookintent",0);put("package_visible",true); }
        catch(Exception error) { put("package_visible",false);put("package_error",error.toString()); }
        try(Cursor cursor=getContentResolver().query(Uri.parse("content://com.fourtwo.hookintent.configprovider/config"),null,null,null,null)) {
            put("provider_rows",cursor==null ? -1 : cursor.getCount());
        } catch(Exception error) { put("provider_error",error.toString()); }
        try { bound=bindService(new Intent().setClassName("com.fourtwo.hookintent","com.fourtwo.hookintent.service.MessengerService"),connection,BIND_AUTO_CREATE);put("bind_accepted",bound); }
        catch(Exception error) { put("bind_error",error.toString()); }
    }
    private void put(String key,Object value) {
        try { result.put(key,value);String json=result.toString(2);text.setText(json);getSharedPreferences("probe",0).edit().putString("result",result.toString()).commit(); }
        catch(Exception ignored) { }
    }
    @Override public void onDestroy() {
        if(remote!=null) try {Message unregister=Message.obtain(null,101);unregister.replyTo=reply;remote.send(unregister);}catch(Exception ignored) { }
        if(bound) unbindService(connection);super.onDestroy();
    }
}
