package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.fourtwo.hookintent.R;

public class LoadingOverlay {
    private final AlertDialog dialog;
    private final TextView loadingText;

    public LoadingOverlay(Context context) {
        AppDialogs.ProgressContent content = AppDialogs.progress(context, context.getString(R.string.dialog_loading_apps));
        loadingText = content.message;
        dialog = AppDialogs.builder(context).setView(content).setCancelable(false).create();
        dialog.setCanceledOnTouchOutside(false);
    }

    public void show() { dialog.show(); }
    public void hide() { dialog.dismiss(); }
    public void setLoadingText(String text) { loadingText.setText(text); }
    public String getLoadingText() { return loadingText.getText().toString(); }
}
