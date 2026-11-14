package com.fourtwo.hookintent.widget;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.widget.*;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.actions.*;
import java.util.*;

public class ActionWidgetService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        final int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        return new RemoteViewsFactory() {
            List<QuickAction> values=new ArrayList<>();
            boolean compact=true, shortHeight;
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() {
                android.os.Bundle options=AppWidgetManager.getInstance(ActionWidgetService.this).getAppWidgetOptions(id);
                int width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,110);
                int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,110);
                compact=width < 220 || height < 160;
                shortHeight=height < 90;
                List<QuickAction> next=new ArrayList<>();
                try {
                    Map<String,QuickAction> byId=new HashMap<>();
                    for(QuickAction action:ActionStore.get(ActionWidgetService.this).all()) byId.put(action.id,action);
                    for(String actionId:ActionWidgetProvider.selection(ActionWidgetService.this,id)) {
                        QuickAction action=byId.get(actionId); if(action != null) next.add(action);
                    }
                } catch(Exception error) { android.util.Log.e("ActionWidget","Cannot load actions",error); }
                values=next;
            }
            @Override public RemoteViews getViewAt(int position) {
                if(position < 0 || position >= values.size()) return null;
                QuickAction action=values.get(position);
                RemoteViews view=new RemoteViews(getPackageName(),compact ? R.layout.widget_action_card_compact : R.layout.widget_action_card);
                view.setTextViewText(R.id.widget_action_name,action.name);
                view.setTextViewText(R.id.widget_action_position,(position+1)+" / "+values.size());
                view.setViewVisibility(R.id.widget_action_position,shortHeight ? android.view.View.GONE : android.view.View.VISIBLE);
                view.setInt(R.id.widget_action_name,"setMaxLines",shortHeight ? 1 : 2);
                if(action.icon == null) view.setImageViewResource(R.id.widget_action_icon,R.mipmap.ic_launcher);
                else view.setImageViewBitmap(R.id.widget_action_icon,BitmapFactory.decodeByteArray(action.icon,0,action.icon.length));
                view.setOnClickFillInIntent(R.id.widget_card,new Intent().putExtra("quick_action_id",action.id));
                return view;
            }
            @Override public int getCount() { return values.size(); }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public long getItemId(int position) { return UUID.fromString(values.get(position).id).getMostSignificantBits(); }
            @Override public boolean hasStableIds() { return true; }
            @Override public void onDestroy() { values=new ArrayList<>(); }
        };
    }
}
