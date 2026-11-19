package com.fourtwo.hookintent.ui.replay;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.FragmentActivity;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.replay.FragmentReplayStore;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class ReplayTemplateDialogs {
    private static final Executor STORAGE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ReplayTemplateDialogs() {}

    public static void create(FragmentActivity activity, Bundle capture, Bundle descriptor, Bundle arguments) {
        LinearLayout content = AppDialogs.content(activity);
        AppCompatEditText name = AppDialogs.input(activity);
        name.setHint(R.string.replay_templates_name_optional);
        name.setSingleLine(true);
        content.addView(name);
        AppCompatEditText identifier = identifierInput(activity, R.string.replay_templates_id_new);
        content.addView(identifier);
        addIdentifierHelp(content);
        AlertDialog dialog = AppDialogs.builder(activity)
                .setTitle(R.string.replay_templates_save)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.navigation_replay_generate, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = inputValue(name);
            String id = inputValue(identifier);
            Context app = activity.getApplicationContext();
            submit(dialog, () -> FragmentReplayStore.createIntent(app, capture, descriptor, arguments, title, id),
                    (intent, error) -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (error == null) {
                            dialog.dismiss();
                            showIntent(activity, intent);
                        } else {
                            showError(activity, R.string.navigation_replay_generate_error, error);
                        }
                    });
        });
    }

    static AppCompatEditText identifierInput(Context context, @StringRes int hint) {
        AppCompatEditText input = AppDialogs.input(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(hint);
        return input;
    }

    static void addIdentifierHelp(LinearLayout content) {
        Context context = content.getContext();
        TextView help = new TextView(context);
        help.setText(R.string.replay_templates_id_help);
        help.setTextAppearance(context, R.style.TextAppearance_HookIntent_DialogBody);
        content.addView(help);
    }

    static String inputValue(AppCompatEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    static <T> void submit(AlertDialog dialog, Callable<T> work, BiConsumer<T, Exception> complete) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        dialog.setCancelable(false);
        run(work, (result, error) -> {
            if (!dialog.isShowing()) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            dialog.setCancelable(true);
            complete.accept(result, error);
        });
    }

    static <T> void run(Callable<T> work, BiConsumer<T, Exception> complete) {
        STORAGE.execute(() -> {
            try {
                T result = work.call();
                MAIN.post(() -> complete.accept(result, null));
            } catch (Exception error) {
                MAIN.post(() -> complete.accept(null, error));
            }
        });
    }

    static void showIntent(FragmentActivity activity, Intent intent) {
        showIntent(activity, intent, R.string.navigation_replay_generated_intent);
    }

    public static void showIntent(FragmentActivity activity, Intent intent, @StringRes int title) {
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        AlertDialog dialog = AppDialogs.builder(activity)
                .setTitle(title)
                .setMessage(uri)
                .setPositiveButton(R.string.copy_am_code,
                        (ignored, which) -> copy(activity, title, uri))
                .setNeutralButton(R.string.navigation_replay_start, (ignored, which) -> {
                    try {
                        activity.startActivity(intent);
                    } catch (RuntimeException error) {
                        showError(activity, R.string.navigation_replay_error, error);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }

    static void copy(Context context, @StringRes int label, String text) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(context.getString(label), text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.detail_copied, Toast.LENGTH_SHORT).show();
    }

    public static void showError(Context context, @StringRes int resource, Exception error) {
        String message = error.getMessage();
        Toast.makeText(context, context.getString(resource,
                message == null ? error.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
    }
}
