package com.fourtwo.hookintent.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.replay.ExtrasEditorDialog;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;

final class NavigationReplayDialogs {
    private NavigationReplayDialogs() {}

    static void editInApp(FragmentActivity activity, ItemData record, Bundle descriptor) {
        Bundle arguments = descriptor.getBundle("arguments");
        if (arguments == null) return;
        // Use the existing typed editor locally. This carrier never becomes a launch intent.
        Bundle draft = DataConverter.convertIntentToBundle(new Intent().putExtras(arguments));
        ExtrasEditorDialog.show(activity, draft, R.string.navigation_replay_edit_title,
                R.string.navigation_replay_start, edited -> {
            try {
                Bundle editedArguments = IntentPayloadCodec.decode(edited).getExtras();
                Bundle capture = record.getAppBundle();
                Intent foreground = activity.getPackageManager().getLaunchIntentForPackage(
                        capture.getString("packageName", ""));
                if (foreground == null) {
                    throw new IllegalStateException(activity.getString(R.string.navigation_replay_missing_launcher));
                }
                NavigationReplayTransport.replay(activity, capture, descriptor,
                        editedArguments == null ? Bundle.EMPTY : editedArguments,
                        () -> activity.startActivity(foreground), error -> {
                            if (error != null) showError(activity.getApplicationContext(), error);
                        });
            } catch (RuntimeException error) {
                showError(activity, error);
            }
        });
    }

    private static void showError(Context context, Exception error) {
        String message = error.getMessage();
        Toast.makeText(context, context.getString(R.string.navigation_replay_error,
                message == null ? error.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
    }
}
