package com.fourtwo.hookintent.widget;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.widget.RemoteViews;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.ShortcutActivity;
import java.util.*;
import org.json.JSONArray;

public class ActionWidgetProvider extends AppWidgetProvider {
    public static List<String> selection(Context context,int id) {
        List<String> result=new ArrayList<>();
        try {
            JSONArray values=new JSONArray(context.getSharedPreferences("action_widgets",0).getString("widget_"+id,"[]"));
            for(int i=0;i<values.length();i++) result.add(values.getString(i));
        } catch(Exception ignored) { }
        return result;
    }
    public static void save(Context context,int id,List<String> values) {
        if(!context.getSharedPreferences("action_widgets",0).edit().putString("widget_"+id,new JSONArray(values).toString()).commit())
            throw new IllegalStateException("无法保存桌面卡片配置");
        update(context,AppWidgetManager.getInstance(context),id);
    }
    public static void updateAll(Context context) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        for(int id:manager.getAppWidgetIds(new ComponentName(context,ActionWidgetProvider.class))) update(context,manager,id);
    }
    public static void update(Context context,AppWidgetManager manager,int id) {
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.widget_actions);
        views.setTextViewText(R.id.widget_empty,context.getString(R.string.action_widget_empty));
        views.setImageViewResource(R.id.widget_configure,R.drawable.widget_edit);
        Intent adapter=new Intent(context,ActionWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
                .setData(Uri.parse("jumpreplay-widget-adapter:"+id));
        views.setRemoteAdapter(R.id.action_stack,adapter);
        views.setEmptyView(R.id.action_stack,R.id.widget_empty);
        Intent launch=new Intent(context,ShortcutActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("jumpreplay-widget:"+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setPendingIntentTemplate(R.id.action_stack,PendingIntent.getActivity(context,id,launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)));
        PendingIntent configure=PendingIntent.getActivity(context,id,new Intent(context,ActionWidgetConfigureActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("jumpreplay-widget-config:"+id)),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_configure,configure);
        views.setOnClickPendingIntent(R.id.widget_empty,configure);
        manager.updateAppWidget(id,views);
        manager.notifyAppWidgetViewDataChanged(id,R.id.action_stack);
    }
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) { for(int id:ids) update(context,manager,id); }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,android.os.Bundle options) { update(context,manager,id); }
    @Override public void onDeleted(Context context,int[] ids) {
        android.content.SharedPreferences.Editor editor=context.getSharedPreferences("action_widgets",0).edit();
        for(int id:ids) editor.remove("widget_"+id); editor.apply();
    }
}
