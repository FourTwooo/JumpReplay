package com.fourtwo.hookintent.service;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.manager.HookStatusManager;

public class CaptureTileService extends TileService {
    public static void refresh(Context context) {
        requestListeningState(context, new ComponentName(context, CaptureTileService.class));
    }
    public static void requestAdd(AppCompatActivity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = activity.getSystemService(StatusBarManager.class);
            if (manager != null) {
                manager.requestAddTileService(new ComponentName(activity, CaptureTileService.class),
                        activity.getString(R.string.capture_tile), Icon.createWithResource(activity, R.drawable.capture),
                        activity.getMainExecutor(), result -> Toast.makeText(activity,
                                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                        || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                        ? R.string.capture_tile_added : R.string.capture_tile_manual, Toast.LENGTH_LONG).show());
                return;
            }
        }
        Toast.makeText(activity, R.string.capture_tile_manual, Toast.LENGTH_LONG).show();
    }
    @Override public void onStartListening() {
        HookStatusManager.initialize(this);
        update();
    }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) { unlockAndRun(this::toggle); return; }
        toggle();
    }
    private void toggle() {
        HookStatusManager.initialize(this);
        HookStatusManager.setHook(this, !HookStatusManager.isHook());
        update();
    }
    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.capture_tile));
        tile.setState(HookStatusManager.isHook() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(HookStatusManager.isHook()
                ? R.string.capture_running : R.string.capture_stopped));
        tile.updateTile();
    }
}
