package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** Keeps long forms scrollable while the dialog title and action buttons remain visible. */
public final class DialogScrollView extends ScrollView {
    private boolean fullHeight;
    public void setFullHeight(boolean value) { fullHeight=value; requestLayout(); }
    public DialogScrollView(Context context) { super(context); }
    public DialogScrollView(Context context, AttributeSet attributes) { super(context, attributes); }
    public DialogScrollView(Context context, AttributeSet attributes, int style) { super(context, attributes, style); }

    @Override protected void onMeasure(int width, int height) {
        if (fullHeight) { super.onMeasure(width,height); return; }
        int limit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) {
            limit = Math.min(limit, MeasureSpec.getSize(height));
        }
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
