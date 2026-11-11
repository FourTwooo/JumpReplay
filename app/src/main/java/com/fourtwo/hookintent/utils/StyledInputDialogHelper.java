package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.fourtwo.hookintent.R;

public final class StyledInputDialogHelper {
    private StyledInputDialogHelper() { }

    public interface OnConfirmListener {
        void onConfirm(@NonNull String value, @NonNull AlertDialog dialog);
    }

    public static void showSingleInputDialog(@NonNull Context context,
            @NonNull String titleText, @NonNull String hintText, @Nullable String initialValue,
            @NonNull OnConfirmListener listener) {
        LinearLayout content = AppDialogs.content(context);
        EditText input = AppDialogs.input(context);
        input.setHint(hintText);
        input.setSingleLine(true);
        if (initialValue != null) {
            input.setText(initialValue);
            input.setSelection(initialValue.length());
        }
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = AppDialogs.builder(context)
                .setTitle(titleText).setView(content)
                .setNegativeButton(R.string.cancel, (dismissed, which) -> dismissed.dismiss())
                .setPositiveButton(R.string.ok, null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            // The caller validates and dismisses the form only after accepting the value.
            listener.onConfirm(value, dialog);
        }));
        dialog.show();
    }
}
