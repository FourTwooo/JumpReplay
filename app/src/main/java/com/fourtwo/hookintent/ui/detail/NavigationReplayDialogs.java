package com.fourtwo.hookintent.ui.detail;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.replay.ExtrasEditorDialog;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;
import com.fourtwo.hookintent.replay.ReplayFeedback;
import com.fourtwo.hookintent.ui.replay.ReplayTemplateDialogs;

import java.util.function.Consumer;

final class NavigationReplayDialogs {
    private NavigationReplayDialogs() {}

    static void editInApp(FragmentActivity activity, ItemData record, Bundle descriptor, String feedbackKey) {
        editArguments(activity, descriptor, R.string.navigation_replay_start,
                R.string.navigation_replay_error, editedArguments -> {
            ReplayFeedback.Attempt attempt = ReplayFeedback.begin(activity, feedbackKey);
            try {
                Bundle capture = record.getAppBundle();
                Intent foreground = activity.getPackageManager().getLaunchIntentForPackage(
                        capture.getString("packageName", ""));
                if (foreground == null) {
                    throw new IllegalStateException(activity.getString(R.string.navigation_replay_missing_launcher));
                }
                NavigationReplayTransport.replayAfterLaunch(activity, capture, descriptor, editedArguments,
                        () -> {
                            activity.startActivity(foreground);
                            attempt.sent();
                        }, error -> {
                            if (error == null) attempt.confirmed();
                            else attempt.failed(error);
                            attempt.announce();
                        });
            } catch (RuntimeException error) {
                attempt.failed(error);
                attempt.announce();
            }
        });
    }

    static void generateIntent(FragmentActivity activity, ItemData record, Bundle descriptor) {
        editArguments(activity, descriptor, R.string.navigation_replay_generate,
                R.string.navigation_replay_generate_error, editedArguments ->
                        ReplayTemplateDialogs.create(activity, record.getAppBundle(), descriptor, editedArguments));
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
                ReplayTemplateDialogs.showError(activity, errorResource, error);
            }
        });
    }
}
