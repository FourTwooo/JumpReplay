package com.fourtwo.hookintent.replay;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.R;

/** Entry for a generated replay, scoped to its saved destination and editable argument types. */
public final class FragmentReplayActivity extends AppCompatActivity {
    private ReplayFeedback.Attempt attempt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            finish();
            return;
        }
        Intent input = getIntent();
        attempt = ReplayFeedback.begin(this, FragmentReplayStore.feedbackKey(this, input));
        FragmentReplayStore.openAsync(this, input, this::launchPrepared);
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
                    attempt.sent();
                }, this::complete);
    }

    private void complete(Exception error) {
        if (error == null) attempt.confirmed();
        else attempt.failed(error);
        attempt.announce();
        if (!isFinishing() && !isDestroyed()) finish();
    }
}
