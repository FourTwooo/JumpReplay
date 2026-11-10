package com.fourtwo.hookintent.utils;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.fourtwo.hookintent.R;

public final class StyledInputDialogHelper {

    private StyledInputDialogHelper() {
    }

    public interface OnConfirmListener {
        void onConfirm(@NonNull String value, @NonNull AlertDialog dialog);
    }

    public static void showSingleInputDialog(@NonNull android.content.Context context,
                                             @NonNull String titleText,
                                             @NonNull String hintText,
                                             @Nullable String initialValue,
                                             @NonNull OnConfirmListener listener) {

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        root.setBackground(createRoundRect(context, "#FCFCFD", "#ECEEF2", 24));

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1F2937"));
        root.addView(title);

        EditText input = new EditText(context);
        input.setHint(hintText);
        input.setBackground(null);
        input.setTextSize(14);
        input.setHintTextColor(Color.parseColor("#9CA3AF"));
        input.setTextColor(Color.parseColor("#1F2937"));
        input.setSingleLine(true);
        input.setPadding(0, dp(context, 6), 0, dp(context, 6));
        if (initialValue != null) {
            input.setText(initialValue);
            input.setSelection(initialValue.length());
        }

        LinearLayout inputBox = new LinearLayout(context);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(createRoundRect(context, "#F3F4F6", "#F3F4F6", 18));
        inputBox.setPadding(dp(context, 14), dp(context, 8), dp(context, 12), dp(context, 8));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputBox.addView(input, inputLp);

        LinearLayout.LayoutParams inputBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputBoxLp.topMargin = dp(context, 14);
        root.addView(inputBox, inputBoxLp);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = dp(context, 18);
        root.addView(buttonRow, rowLp);

        Button cancelButton = createDialogActionButton(
                context,
                context.getString(R.string.cancel),
                "#F3F4F6",
                "#E6E8EC",
                "#6B7280"
        );

        Button confirmButton = createDialogActionButton(
                context,
                context.getString(R.string.ok),
                "#EAF1FF",
                "#D6E4FF",
                "#2E6EF7"
        );

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(context, 38)
        );
        cancelLp.rightMargin = dp(context, 8);

        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(context, 38)
        );
        confirmLp.leftMargin = dp(context, 8);

        buttonRow.addView(cancelButton, cancelLp);
        buttonRow.addView(confirmButton, confirmLp);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .create();

        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        confirmButton.setOnClickListener(v -> {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            listener.onConfirm(value, dialog);
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private static Button createDialogActionButton(@NonNull android.content.Context context,
                                                   @NonNull String text,
                                                   @NonNull String fillColor,
                                                   @NonNull String strokeColor,
                                                   @NonNull String textColor) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(dp(context, 78));
        button.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(Color.parseColor(textColor));
        button.setBackground(createRoundRect(context, fillColor, strokeColor, 999));
        return button;
    }

    private static GradientDrawable createRoundRect(@NonNull android.content.Context context,
                                                    @NonNull String fillColor,
                                                    @Nullable String strokeColor,
                                                    int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.parseColor(fillColor));
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != null) {
            drawable.setStroke(dp(context, 1), Color.parseColor(strokeColor));
        }
        return drawable;
    }

    private static int dp(@NonNull android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}