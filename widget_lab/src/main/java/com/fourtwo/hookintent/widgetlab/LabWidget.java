package com.fourtwo.hookintent.widgetlab;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.Uri;
import android.widget.RemoteViews;

/** The ordinary, non-root desktop alternative. Its page transitions remain system StackView animations. */
public final class LabWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for(int id : ids) {
            android.util.Log.i("RotaryWidgetLab", "Updating real widget " + id);
            RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.widget_rotary_native);
            views.setRemoteAdapter(R.id.native_stack,new Intent(context,NativeCardService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("rotary-lab-adapter:"+id)));
            views.setPendingIntentTemplate(R.id.native_stack,PendingIntent.getActivity(context,id,
                    new Intent(context,LabLaunchActivity.class).setData(Uri.parse("rotary-lab:"+id)),
                    PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT>=31 ? PendingIntent.FLAG_MUTABLE : 0)));
            manager.updateAppWidget(id,views);
            manager.notifyAppWidgetViewDataChanged(id,R.id.native_stack);
        }
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,android.os.Bundle options) {
        onUpdate(context,manager,new int[]{id});
    }
}
