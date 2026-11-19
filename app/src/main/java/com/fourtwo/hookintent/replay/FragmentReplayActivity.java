package com.fourtwo.hookintent.replay;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.R;

/** Entry for a local, preconfigured replay. The incoming Intent only supplies its capability URI. */
public final class FragmentReplayActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            finish();
            return;
        }
        FragmentReplayStore.openAsync(this, getIntent(), this::launchPrepared);
    }

    private void launchPrepared(FragmentReplayStore.Prepared replay, Exception error) {
        if (isFinishing() || isDestroyed()) return;
        if (error != null) {
            complete(error);
            return;
        }
        NavigationReplayTransport.replayAfterLaunch(this, replay.source, replay.descriptor, replay.arguments,
                () -> {
                    if (isFinishing() || isDestroyed()) throw new IllegalStateException("重放入口已关闭");
                    Intent launch = getPackageManager().getLaunchIntentForPackage(
                            replay.source.getString("packageName"));
                    if (launch == null) throw new IllegalStateException(
                            getString(R.string.navigation_replay_missing_launcher));
                    startActivity(launch);
                }, this::complete);
    }

    private void complete(Exception error) {
        if (error != null) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            Toast.makeText(getApplicationContext(), getString(R.string.navigation_replay_error, detail),
                    Toast.LENGTH_LONG).show();
        }
        if (!isFinishing() && !isDestroyed()) finish();
    }
}
