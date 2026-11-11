package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;
import com.fourtwo.hookintent.R;

/** Dialog-only styling, shared by the main app and the transparent shortcut process. */
public final class AppDialogs {
    private AppDialogs() { }

    @NonNull public static Context context(@NonNull Context context) {
        return new ContextThemeWrapper(context, R.style.Theme_HookIntent_Dialog);
    }

    @NonNull public static AlertDialog.Builder builder(@NonNull Context context) {
        return new AlertDialog.Builder(context, R.style.Theme_HookIntent_Dialog);
    }

    @NonNull public static LinearLayout content(@NonNull Context context) {
        LinearLayout content = new LinearLayout(context(context));
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        content.setPadding(padding, padding, padding, padding);
        return content;
    }

    @NonNull public static AppCompatEditText input(@NonNull Context context) {
        return new AppCompatEditText(context(context));
    }

    /** Weighted lists need bounded space; short input forms retain their natural height. */
    public static void sizeForList(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            int height = Math.round(dialog.getContext().getResources().getDisplayMetrics().heightPixels * 0.80f);
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, height);
        }
    }

    /** The colour picker owns a platform dialog. Keep its controls, but share the same chrome. */
    public static void styleExternal(@NonNull android.app.AlertDialog dialog) {
        Context context = dialog.getContext();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.dialog_surface_background));
        }
        for (int which : new int[]{android.content.DialogInterface.BUTTON_POSITIVE,
                android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setMinHeight(dp(context, 48));
            button.setMinimumHeight(dp(context, 48));
            button.setIncludeFontPadding(true);
            button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
            button.setTextColor(ContextCompat.getColor(context,
                    which == android.content.DialogInterface.BUTTON_POSITIVE ? R.color.dialog_accent : R.color.dialog_secondary));
            button.setBackgroundResource(R.drawable.dialog_button_background);
            button.setStateListAnimator(null);
            button.setElevation(0);
            ViewGroup.LayoutParams parameters = button.getLayoutParams();
            parameters.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            button.setLayoutParams(parameters);
        }
    }

    @NonNull public static ProgressContent progress(@NonNull Context context, @NonNull String message) {
        return new ProgressContent(context, message);
    }

    public static final class ProgressContent extends LinearLayout {
        public final TextView message;
        private ProgressContent(Context context, String text) {
            super(AppDialogs.context(context));
            Context themed = getContext();
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padding = dp(themed, 20);
            setPadding(padding, padding, padding, padding);
            ProgressBar progress = new ProgressBar(themed);
            progress.setIndeterminateTintList(ColorStateList.valueOf(ContextCompat.getColor(themed, R.color.dialog_accent)));
            addView(progress, new LayoutParams(dp(themed, 28), dp(themed, 28)));
            message = new TextView(themed);
            message.setText(text);
            message.setTextAppearance(themed, R.style.TextAppearance_HookIntent_DialogBody);
            LayoutParams parameters = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            parameters.setMarginStart(dp(themed, 16));
            addView(message, parameters);
        }
    }

    public static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
