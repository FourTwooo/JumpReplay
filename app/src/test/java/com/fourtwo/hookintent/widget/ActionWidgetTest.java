package com.fourtwo.hookintent.widget;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.widget.RemoteViewsService;
import com.fourtwo.hookintent.actions.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=33)
public class ActionWidgetTest {
    @Test public void resizingPreservesActionIdentityAndKeepsIntentContentsOffTheDesktop() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        java.lang.reflect.Field field=ActionStore.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);
        ActionStore store=ActionStore.get(context);
        try {
            QuickAction action=new QuickAction(UUID.randomUUID().toString(),"我的常用动作", "demo://open?token=private-value",new String[0],false,"root",null,null);
            store.save(action);
            AppWidgetManager manager=AppWidgetManager.getInstance(context);
            int id=Shadows.shadowOf(manager).createWidget(ActionWidgetProvider.class,com.fourtwo.hookintent.R.layout.widget_actions);
            ActionWidgetProvider.save(context,id,Collections.singletonList(action.id));
            ActionWidgetService service=Robolectric.buildService(ActionWidgetService.class).create().get();
            RemoteViewsService.RemoteViewsFactory factory=service.onGetViewFactory(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));
            android.os.Bundle options=new android.os.Bundle();
            long stableId=0; int firstLayout=0;
            for(int[] size:new int[][]{{140,110},{300,220},{140,52}}) {
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,size[0]);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,size[1]);
                manager.updateAppWidgetOptions(id,options);factory.onDataSetChanged();
                assertEquals(1,factory.getCount());
                android.widget.RemoteViews card=factory.getViewAt(0);
                android.view.View view=card.apply(context,new android.widget.FrameLayout(context));
                android.widget.TextView name=view.findViewById(com.fourtwo.hookintent.R.id.widget_action_name);
                // Reproduce StackView's AT_MOST child measurement. Short names must not shrink
                // the card and make the stack spread into separate tiles on the real launcher.
                int measuredWidth=Math.round(size[0]*0.9f);
                int measuredHeight=Math.round(size[1]*0.9f);
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(measuredWidth,android.view.View.MeasureSpec.AT_MOST),
                        android.view.View.MeasureSpec.makeMeasureSpec(measuredHeight,android.view.View.MeasureSpec.AT_MOST));
                assertEquals(measuredWidth,view.getMeasuredWidth());
                assertEquals(measuredHeight,view.getMeasuredHeight());
                assertEquals(action.name,name.getText().toString());
                assertFalse(viewText(view).contains("private-value"));
                assertEquals(Collections.singletonList(action.id),ActionWidgetProvider.selection(context,id));
                if(firstLayout == 0) { firstLayout=card.getLayoutId();stableId=factory.getItemId(0); }
                else assertEquals(stableId,factory.getItemId(0));
                if(size[0] == 300) assertNotEquals(firstLayout,card.getLayoutId());
                if(size[1] == 52) {
                    assertEquals(android.view.View.GONE,view.findViewById(com.fourtwo.hookintent.R.id.widget_action_position).getVisibility());
                    assertEquals(1,name.getMaxLines());
                }
            }
        } finally { store.close(); }
    }
    @Test public void portraitUsesMaximumHeightAndLandscapeUsesMaximumWidth() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        java.lang.reflect.Field field=ActionStore.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);
        ActionStore store=ActionStore.get(context);
        try {
            QuickAction action=new QuickAction(UUID.randomUUID().toString(),"A","demo://one",new String[0],false,"root",null,null);
            store.save(action);
            AppWidgetManager manager=AppWidgetManager.getInstance(context);
            int id=Shadows.shadowOf(manager).createWidget(ActionWidgetProvider.class,com.fourtwo.hookintent.R.layout.widget_actions);
            ActionWidgetProvider.save(context,id,Collections.singletonList(action.id));
            android.os.Bundle options=new android.os.Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,138);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,283);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,51);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,113);
            manager.updateAppWidgetOptions(id,options);
            ActionWidgetService service=Robolectric.buildService(ActionWidgetService.class).create().get();
            RemoteViewsService.RemoteViewsFactory factory=service.onGetViewFactory(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));
            for(String orientation:new String[]{"port","land"}) {
                RuntimeEnvironment.setQualifiers(orientation);
                factory.onDataSetChanged();
                android.view.View card=factory.getViewAt(0).apply(context,new android.widget.FrameLayout(context));
                int expectedHeight=orientation.equals("port") ? 113 : 51;
                int expectedWidth=orientation.equals("port") ? 138 : 283;
                float density=context.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(expectedHeight*density),card.getMinimumHeight());
                assertEquals(Math.round(expectedWidth*density),((android.widget.TextView)card.findViewById(com.fourtwo.hookintent.R.id.widget_action_name)).getMinWidth());
                assertEquals(orientation.equals("port") ? android.view.View.VISIBLE : android.view.View.GONE,
                        card.findViewById(com.fourtwo.hookintent.R.id.widget_action_position).getVisibility());
            }
        } finally { RuntimeEnvironment.setQualifiers("port");store.close(); }
    }
    private String viewText(android.view.View view) {
        StringBuilder text=new StringBuilder();
        if(view instanceof android.widget.TextView) text.append(((android.widget.TextView)view).getText());
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) text.append(viewText(group.getChildAt(i)));
        }
        return text.toString();
    }
    @Test public void widgetsHaveIndependentSelectionsAndDeletedActionsDisappear() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        java.lang.reflect.Field field=ActionStore.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,null);
        ActionStore store=ActionStore.get(context);
        try {
            QuickAction one=new QuickAction(UUID.randomUUID().toString(),"one","demo://one",new String[0],false,"root",null,null);
            QuickAction two=new QuickAction(UUID.randomUUID().toString(),"two","demo://two",new String[0],false,"root",null,null);
            store.save(one); store.save(two);
            int firstId=Shadows.shadowOf(AppWidgetManager.getInstance(context)).createWidget(ActionWidgetProvider.class,com.fourtwo.hookintent.R.layout.widget_actions);
            int secondId=Shadows.shadowOf(AppWidgetManager.getInstance(context)).createWidget(ActionWidgetProvider.class,com.fourtwo.hookintent.R.layout.widget_actions);
            ActionWidgetProvider.save(context,firstId,Arrays.asList(two.id,one.id));
            ActionWidgetProvider.save(context,secondId,Collections.singletonList(one.id));
            assertEquals(Arrays.asList(two.id,one.id),ActionWidgetProvider.selection(context,firstId));
            assertEquals(Collections.singletonList(one.id),ActionWidgetProvider.selection(context,secondId));
            ActionWidgetService service=Robolectric.buildService(ActionWidgetService.class).create().get();
            RemoteViewsService.RemoteViewsFactory first=service.onGetViewFactory(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,firstId));
            RemoteViewsService.RemoteViewsFactory second=service.onGetViewFactory(new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,secondId));
            first.onCreate();second.onCreate(); assertEquals(2,first.getCount());assertEquals(1,second.getCount());
            store.delete(one.id);first.onDataSetChanged();second.onDataSetChanged();
            assertEquals(1,first.getCount());assertEquals(0,second.getCount());
            ActionWidgetProvider provider=new ActionWidgetProvider();provider.onDeleted(context,new int[]{secondId});
            assertTrue(ActionWidgetProvider.selection(context,secondId).isEmpty());assertEquals(2,ActionWidgetProvider.selection(context,firstId).size());
        } finally { store.close(); }
    }
}
