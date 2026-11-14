package com.fourtwo.hookintent.service;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import com.fourtwo.hookintent.MainActivity;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.manager.CaptureMonitor;
import com.fourtwo.hookintent.manager.HookStatusManager;
import com.fourtwo.hookintent.utils.AppDialogs;

/** User-started capture session. Binding keeps the receive endpoint alive without a visible Activity. */
public class CaptureSessionService extends Service {
    public static final String STOP = "capture.stop", PAUSE = "capture.pause";
    private static final int NOTIFICATION = 1700;
    private static CaptureSessionService instance;
    private static boolean mainVisible;
    private final ServiceConnection receiver = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { update(); }
        @Override public void onServiceDisconnected(ComponentName name) { update(); }
    };
    private boolean bound;
    private WindowManager windows;
    private View overlay;
    private TextView summary, status;
    private Button pause;
    private final Observer<CaptureMonitor.Snapshot> recordsObserver = value -> update();
    private final Observer<Boolean> stateObserver = value -> {
        if (!Boolean.TRUE.equals(value)) {
            hideOverlay();
            stopForeground(true);
            stopSelf();
        } else update();
    };
    private final Observer<Integer> connectionObserver = value -> update();
    private boolean starting = true;
    public static boolean isRunning() { return instance != null; }

    public static void refresh(Context context) {
        if (HookStatusManager.isHook()) {
            try { ContextCompat.startForegroundService(context, new Intent(context, CaptureSessionService.class)); }
            catch (RuntimeException error) {
                HookStatusManager.setHook(false);
                Toast.makeText(context, context.getString(R.string.capture_session_error,error.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else context.stopService(new Intent(context, CaptureSessionService.class));
    }
    public static void mainVisible(boolean visible) {
        mainVisible = visible;
        if (instance != null) instance.update();
    }
    public static boolean monitorEnabled(Context context) {
        return context.getSharedPreferences("capture_control", 0).getBoolean("monitor", false);
    }
    public static void monitorEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("capture_control", 0).edit().putBoolean("monitor", enabled).apply();
        if (instance != null) instance.update();
    }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        HookStatusManager.initialize(this);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(new NotificationChannel(
                "capture", getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification());
        bound = bindService(new Intent(this, MessengerService.class), receiver, BIND_AUTO_CREATE);
        windows = getSystemService(WindowManager.class);
        starting = false;
        CaptureMonitor.changes().observeForever(recordsObserver);
        HookStatusManager.state().observeForever(stateObserver);
        MessengerService.connectionCount.observeForever(connectionObserver);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action) || PAUSE.equals(action) || !HookStatusManager.isHook()) {
            HookStatusManager.setHook(false);
            hideOverlay(); stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }
        update();
        return START_STICKY;
    }
    private PendingIntent control(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, CaptureSessionService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent open() {
        return PendingIntent.getActivity(this, 1700, new Intent(this, MainActivity.class)
                        .putExtra("show_latest_capture", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private String description() {
        CaptureMonitor.Snapshot value = CaptureMonitor.changes().getValue();
        String text = getString(R.string.capture_session_count,value == null ? 0 : value.count);
        if (value != null && value.time > 0) text += " · " + value.source + " · "
                + android.text.format.DateFormat.format("HH:mm:ss", value.time);
        Integer connected=MessengerService.connectionCount.getValue();
        text += "\n" + getString(R.string.capture_connected_count,connected == null ? 0 : connected);
        return text;
    }
    private Notification notification() {
        return new NotificationCompat.Builder(this, "capture").setSmallIcon(R.drawable.capture)
                .setContentTitle(getString(HookStatusManager.isHook() ? R.string.capture_running : R.string.capture_stopped))
                .setContentText(description()).setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, getString(HookStatusManager.isHook() ? R.string.capture_pause : R.string.capture_resume), control(PAUSE))
                .addAction(0, getString(R.string.capture_stop), control(STOP)).build();
    }
    private void update() {
        if (starting) return;
        if (!HookStatusManager.isHook()) { hideOverlay(); return; }
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        if (!mainVisible && monitorEnabled(this) && Settings.canDrawOverlays(this)) showOverlay();
        else hideOverlay();
        if (overlay != null) {
            summary.setText(description());
            status.setText(getString(HookStatusManager.isHook() ? R.string.capture_running : R.string.capture_stopped));
            pause.setText(getString(HookStatusManager.isHook() ? R.string.capture_pause : R.string.capture_resume));
        }
    }
    private void showOverlay() {
        if (overlay != null || windows == null) return;
        Context themed = AppDialogs.context(this);
        LinearLayout content = AppDialogs.content(themed);
        content.setPadding(dp(16), dp(12), dp(16), dp(8));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(ContextCompat.getColor(themed, R.color.dialog_surface));
        background.setCornerRadius(dp(16)); background.setStroke(dp(1), ContextCompat.getColor(themed,R.color.dialog_border));
        content.setBackground(background);
        status = new TextView(themed); status.setTextAppearance(themed,R.style.TextAppearance_HookIntent_DialogBody);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(status);
        summary = new TextView(themed); summary.setTextAppearance(themed,R.style.TextAppearance_HookIntent_DialogBody);
        summary.setMaxLines(3); content.addView(summary);
        LinearLayout buttons = new LinearLayout(themed);
        pause = new Button(themed, null, 0, R.style.Widget_HookIntent_DialogNegativeButton);
        pause.setOnClickListener(v -> HookStatusManager.setHook(this, false));
        Button view = new Button(themed, null, 0, R.style.Widget_HookIntent_DialogNegativeButton);
        view.setText(R.string.capture_view_latest);
        view.setOnClickListener(v -> { try { open().send(); } catch (PendingIntent.CanceledException ignored) { } });
        Button close = new Button(themed, null, 0, R.style.Widget_HookIntent_DialogNegativeButton);
        close.setText("×"); close.setContentDescription(getString(R.string.capture_monitor_hide));
        close.setOnClickListener(v -> monitorEnabled(this, false));
        buttons.addView(pause, new LinearLayout.LayoutParams(0,-2,1));
        buttons.addView(view, new LinearLayout.LayoutParams(0,-2,1.5f));
        buttons.addView(close, new LinearLayout.LayoutParams(dp(48),-2));
        content.addView(buttons);
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(dp(280),WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START;
        layout.x = getSharedPreferences("capture_control",0).getInt("monitor_x",dp(12));
        layout.y = getSharedPreferences("capture_control",0).getInt("monitor_y",dp(130));
        status.setPadding(0,dp(4),0,dp(8));
        status.setOnTouchListener(new View.OnTouchListener() {
            float x,y; int startX,startY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    x=event.getRawX();y=event.getRawY();startX=layout.x;startY=layout.y;return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int width=getResources().getDisplayMetrics().widthPixels, height=getResources().getDisplayMetrics().heightPixels;
                    layout.x=Math.max(0,Math.min(width-dp(280),startX+(int)(event.getRawX()-x)));
                    layout.y=Math.max(0,Math.min(height-content.getHeight(),startY+(int)(event.getRawY()-y)));
                    if (overlay != null) windows.updateViewLayout(overlay,layout); return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    getSharedPreferences("capture_control",0).edit().putInt("monitor_x",layout.x).putInt("monitor_y",layout.y).apply();
                    return true;
                }
                return false;
            }
        });
        try { windows.addView(content,layout); overlay=content; }
        catch (RuntimeException error) { monitorEnabled(this,false); }
    }
    private int dp(int value) { return AppDialogs.dp(this,value); }
    private void hideOverlay() {
        if (overlay != null) { try { windows.removeView(overlay); } catch (IllegalArgumentException ignored) { } overlay=null; }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        starting=true;
        CaptureMonitor.changes().removeObserver(recordsObserver);
        HookStatusManager.state().removeObserver(stateObserver);
        MessengerService.connectionCount.removeObserver(connectionObserver);
        hideOverlay();
        if (bound) unbindService(receiver);
        instance=null;
        stopForeground(true);
        super.onDestroy();
    }
}
