package com.fourtwo.hookintent.widget;

import android.appwidget.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.*;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.actions.*;
import com.fourtwo.hookintent.utils.AppDialogs;
import java.util.*;

public class ActionWidgetConfigureActivity extends AppCompatActivity implements ActionListAdapter.Listener {
    private int widgetId;
    private ActionListAdapter adapter;
    private List<String> pendingSelection;
    private boolean ready;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setResult(RESULT_CANCELED);
        widgetId=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info=AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        if(widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info == null
                || !new ComponentName(this,ActionWidgetProvider.class).equals(info.provider)) { finish(); return; }
        pendingSelection=state != null ? state.getStringArrayList("selection") : null;
        if(pendingSelection == null) pendingSelection=ActionWidgetProvider.selection(this,widgetId);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,R.color.screen_bg)); setContentView(root);
        Toolbar toolbar=new Toolbar(new androidx.appcompat.view.ContextThemeWrapper(this,R.style.Theme_HookIntent_AppBarOverlay)); toolbar.setTitle(R.string.action_widget_select);
        toolbar.setPopupTheme(R.style.Theme_HookIntent_PopupOverlay);
        toolbar.setNavigationIcon(R.drawable.action_back); toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,R.color.screen_toolbar));
        toolbar.setTitleTextColor(android.graphics.Color.WHITE);
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,AppDialogs.dp(this,56)));
        TextView hint=new TextView(this); hint.setText(R.string.action_widget_instructions);
        hint.setPadding(dp(20),dp(12),dp(20),dp(12)); hint.setTextColor(androidx.core.content.ContextCompat.getColor(this,R.color.screen_secondary)); root.addView(hint);
        Button add=new Button(this,null,0,R.style.Widget_HookIntent_DialogNegativeButton); add.setText(R.string.action_add);
        add.setOnClickListener(v -> startActivity(new Intent(this,ActionEditorActivity.class))); root.addView(add);
        RecyclerView list=new RecyclerView(this); list.setLayoutManager(new LinearLayoutManager(this)); list.setPadding(dp(12),0,dp(12),0);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        adapter=new ActionListAdapter(true,this); list.setAdapter(adapter); adapter.attach(list);
        Button save=new Button(this,null,0,R.style.Widget_HookIntent_DialogButton); save.setText(getString(R.string.action_widget_save));
        LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,-2); button.setMargins(dp(16),dp(8),dp(16),dp(16)); root.addView(save,button);
        save.setOnClickListener(v -> {
            List<String> selection=adapter.ids(true);
            if(!ready || selection.isEmpty()) { Toast.makeText(this,getString(R.string.action_widget_select_required),Toast.LENGTH_LONG).show(); return; }
            try {
                ActionWidgetProvider.save(this,widgetId,selection);
                setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)); finish();
            } catch(Exception error) { Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show(); }
        });
    }
    @Override protected void onResume() {
        super.onResume(); if(adapter == null) return;
        List<String> selected=ready ? adapter.ids(true) : new ArrayList<>(pendingSelection);
        List<String> order=ready ? adapter.ids(false) : new ArrayList<>(pendingSelection);
        ActionStore.IO.execute(() -> {
            try {
                List<QuickAction> values=ActionStore.get(this).all();
                Map<String,QuickAction> map=new LinkedHashMap<>(); for(QuickAction value:values) map.put(value.id,value);
                List<QuickAction> sorted=new ArrayList<>(); for(String id:order) { QuickAction value=map.remove(id); if(value != null) sorted.add(value); }
                sorted.addAll(map.values());
                main.post(() -> { if(!isFinishing() && !isDestroyed()) { adapter.submit(sorted,selected); ready=true; } });
            } catch(Exception error) { main.post(() -> Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show()); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("selection",new ArrayList<>(ready && adapter != null ? adapter.ids(true)
                : pendingSelection == null ? java.util.Collections.emptyList() : pendingSelection)); super.onSaveInstanceState(state);
    }
    private int dp(int value) { return AppDialogs.dp(this,value); }
    @Override public void open(QuickAction action) { }
    @Override public void run(QuickAction action) { }
    @Override public void options(QuickAction action) { }
    @Override public void reordered(List<String> ids) { }
}
