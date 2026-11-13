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
