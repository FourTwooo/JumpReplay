package com.fourtwo.hookintent.service;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.manager.HookStatusManager;

public class CaptureTileService extends TileService {
    public static void refresh(Context context) {
        requestListeningState(context, new ComponentName(context, CaptureTileService.class));
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
