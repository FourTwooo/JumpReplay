package com.fourtwo.hookintent.ui.detail;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.replay.ExtrasEditorDialog;
import com.fourtwo.hookintent.replay.FragmentReplayStore;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.util.function.Consumer;

final class NavigationReplayDialogs {
    private NavigationReplayDialogs() {}

    static void editInApp(FragmentActivity activity, ItemData record, Bundle descriptor) {
        editArguments(activity, descriptor, R.string.navigation_replay_start,
                R.string.navigation_replay_error, editedArguments -> {
            Bundle capture = record.getAppBundle();
            Intent foreground = activity.getPackageManager().getLaunchIntentForPackage(
                    capture.getString("packageName", ""));
            if (foreground == null) {
                throw new IllegalStateException(activity.getString(R.string.navigation_replay_missing_launcher));
            }
            NavigationReplayTransport.replay(activity, capture, descriptor, editedArguments,
                    () -> activity.startActivity(foreground), error -> {
                        if (error != null) showError(activity.getApplicationContext(),
                                R.string.navigation_replay_error, error);
                    });
        });
    }

    static void generateIntent(FragmentActivity activity, ItemData record, Bundle descriptor) {
        editArguments(activity, descriptor, R.string.navigation_replay_generate,
                R.string.navigation_replay_generate_error, editedArguments -> {
            FragmentReplayStore.createIntentAsync(activity.getApplicationContext(),
                    record.getAppBundle(), descriptor, editedArguments, (intent, error) -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (error == null) showGeneratedIntent(activity, intent);
                        else showError(activity, R.string.navigation_replay_generate_error, error);
                    });
        });
    }

    private static void editArguments(FragmentActivity activity, Bundle descriptor, @StringRes int confirm,
                                      @StringRes int errorResource, Consumer<Bundle> onEdited) {
        Bundle arguments = descriptor.getBundle("arguments");
        if (arguments == null) return;
        // The typed editor uses a local Intent to carry the argument draft.
        Bundle draft = DataConverter.convertIntentToBundle(new Intent().putExtras(arguments));
        ExtrasEditorDialog.show(activity, draft, R.string.navigation_replay_edit_title, confirm, edited -> {
            try {
                Bundle editedArguments = IntentPayloadCodec.decode(edited).getExtras();
                onEdited.accept(editedArguments == null ? Bundle.EMPTY : editedArguments);
            } catch (RuntimeException error) {
                showError(activity, errorResource, error);
            }
        });
    }

    private static void showGeneratedIntent(FragmentActivity activity, Intent intent) {
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        AlertDialog dialog = AppDialogs.builder(activity)
                .setTitle(R.string.navigation_replay_generated_intent)
                .setMessage(uri)
                .setPositiveButton(R.string.copy_am_code, (ignored, which) -> copyUri(activity, uri))
                .setNeutralButton(R.string.navigation_replay_start, (ignored, which) -> {
                    try {
                        activity.startActivity(intent);
                    } catch (RuntimeException error) {
                        showError(activity, R.string.navigation_replay_error, error);
                    }
                })
                .setNegativeButton(R.string.extras_cancel, null)
                .show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }

    private static void copyUri(Context context, String uri) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(context.getString(R.string.navigation_replay_generated_intent), uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.detail_copied, Toast.LENGTH_SHORT).show();
    }

    private static void showError(Context context, @StringRes int resource, Exception error) {
        String message = error.getMessage();
        Toast.makeText(context, context.getString(resource,
                message == null ? error.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
    }
}
