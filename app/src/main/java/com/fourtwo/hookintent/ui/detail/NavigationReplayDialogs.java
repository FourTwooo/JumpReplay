package com.fourtwo.hookintent.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.FragmentActivity;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.replay.ExtrasEditorDialog;
import com.fourtwo.hookintent.replay.NavigationReplay;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.util.LinkedHashMap;
import java.util.Map;

final class NavigationReplayDialogs {
    private NavigationReplayDialogs() {}

    static void editBinding(FragmentActivity activity, ItemData record, Runnable onChanged) {
        NavigationReplay.Binding binding = NavigationReplay.getBinding(activity, record);
        LinearLayout content = AppDialogs.content(activity);
        AppCompatEditText uri = AppDialogs.input(activity);
        uri.setHint(R.string.navigation_binding_uri);
        uri.setMaxLines(4);
        content.addView(uri);
        AppCompatEditText mapping = AppDialogs.input(activity);
        mapping.setHint(R.string.navigation_binding_mapping);
        mapping.setMaxLines(6);
        content.addView(mapping);
        if (binding != null) {
            uri.setText(binding.getIntentUri());
            StringBuilder lines = new StringBuilder();
            for (Map.Entry<String, String> entry : binding.getArgumentToExtra().entrySet()) {
                if (lines.length() > 0) lines.append('\n');
                lines.append(entry.getKey()).append('=').append(entry.getValue());
            }
            mapping.setText(lines);
        }
        AlertDialog.Builder builder = AppDialogs.builder(activity).setTitle(R.string.navigation_binding_title)
                .setView(content).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.extras_save, null);
        if (binding != null) builder.setNeutralButton(R.string.navigation_binding_remove, (dialog, which) -> {
            NavigationReplay.removeBinding(activity, record);
            onChanged.run();
        });
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                NavigationReplay.saveBinding(activity, record, uri.getText().toString(),
                        parseMapping(activity, mapping.getText().toString()));
                dialog.dismiss();
                onChanged.run();
            } catch (IllegalArgumentException error) {
                Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static Map<String, String> parseMapping(Context context, String input) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : input.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("=", -1);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()
                    || result.putIfAbsent(parts[0].trim(), parts[1].trim()) != null) {
                throw new IllegalArgumentException(context.getString(R.string.navigation_binding_invalid_mapping));
            }
        }
        return result;
    }

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
