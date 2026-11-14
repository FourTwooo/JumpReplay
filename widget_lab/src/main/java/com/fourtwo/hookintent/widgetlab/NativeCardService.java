package com.fourtwo.hookintent.widgetlab;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Bundle;
import android.widget.*;

public final class NativeCardService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        return new RemoteViewsFactory() {
            private int width,height;
            private RotaryCardRenderer renderer;
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() {
                Bundle options=AppWidgetManager.getInstance(NativeCardService.this).getAppWidgetOptions(id);
                boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
                int w=options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,138);
                int h=options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,243);
                float density=getResources().getDisplayMetrics().density;
                width=Math.round(Math.max(40,Math.min(140,w))*density);
                height=Math.round(Math.max(40,Math.min(373,h))*density);
                renderer=new RotaryCardRenderer(NativeCardService.this);
            }
            @Override public RemoteViews getViewAt(int position) {
                if(position<0 || position>=getCount()) return null;
                Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                renderer.draw(new Canvas(image),width,height,position);
                RemoteViews view=new RemoteViews(getPackageName(),R.layout.native_card);
                view.setImageViewBitmap(R.id.native_card,image);
                view.setContentDescription(R.id.native_card,RotaryCardRenderer.NAMES[position]);
                view.setOnClickFillInIntent(R.id.native_card,new Intent().putExtra("lab_index",position));
                return view;
            }
            @Override public int getCount() { return RotaryCardRenderer.NAMES.length; }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return true; }
            @Override public void onDestroy() { renderer=null; }
        };
    }
}
