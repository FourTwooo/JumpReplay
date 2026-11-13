package com.fourtwo.hookintent.actions;

import android.appwidget.AppWidgetManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.*;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.*;
import com.fourtwo.hookintent.widget.ActionWidgetProvider;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ActionsFragment extends Fragment implements ActionListAdapter.Listener {
    private ActionListAdapter adapter;
    private View empty;
    private int generation;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> export=registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),uri -> { if(uri != null) writeBackup(uri); });
    private final ActivityResultLauncher<String[]> importFile=registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),uri -> { if(uri != null) readBackup(uri); });
    @Override public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle state) {
        return inflater.inflate(R.layout.fragment_actions,container,false);
    }
    @Override public void onViewCreated(View view,Bundle state) {
        RecyclerView list=view.findViewById(R.id.actions_list); list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter=new ActionListAdapter(false,this); list.setAdapter(adapter); adapter.attach(list);
        empty=view.findViewById(R.id.actions_empty);
        view.findViewById(R.id.action_add_button).setOnClickListener(v -> startActivity(new Intent(requireContext(),ActionEditorActivity.class)));
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override public void onCreateMenu(Menu menu,MenuInflater inflater) {
                menu.clear(); menu.add(0,1,0,R.string.action_import); menu.add(0,2,1,R.string.action_export); menu.add(0,3,2,R.string.action_widget_add);
            }
            @Override public boolean onMenuItemSelected(MenuItem item) {
                if(item.getItemId() == 1) {
                    AppDialogs.builder(requireContext()).setTitle(R.string.action_import)
                            .setItems(new String[]{"输入或粘贴 Scheme / Intent URI","导入动作备份文件"},(dialog,index) -> {
                                if(index == 0) pasteUri(); else importFile.launch(new String[]{"application/json","text/plain","application/octet-stream"});
                            }).show(); return true;
                }
                if(item.getItemId() == 2) { export.launch("JumpReplay-actions.json"); return true; }
                if(item.getItemId() == 3) { pinWidget(); return true; }
                return false;
            }
        },getViewLifecycleOwner(),Lifecycle.State.RESUMED);
    }
    private void pasteUri() {
        EditText input=AppDialogs.input(requireContext()); input.setHint(R.string.action_content); input.setMinLines(3); input.setMaxLines(7);
        LinearLayout content=AppDialogs.content(requireContext()); content.addView(input);
        androidx.appcompat.app.AlertDialog dialog=AppDialogs.builder(requireContext()).setTitle(R.string.action_import)
                .setView(content).setNegativeButton(R.string.cancel,null).setPositiveButton("下一步",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(button -> {
            String uri=input.getText().toString().trim();
            try {
                new QuickAction("draft","draft",uri,new String[0],false,"root",null,null).validate();
                openUriEditor(uri); dialog.dismiss();
            } catch(Exception error) { input.setError(error.getMessage()); }
        })); dialog.show();
    }
    @Override public void onResume() { super.onResume(); reload(); }
    private void reload() {
        int request=++generation; Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try {
                List<QuickAction> values=ActionStore.get(context).all();
                main.post(() -> { if(getView() != null && generation == request) { adapter.submit(values,null); empty.setVisibility(values.isEmpty() ? View.VISIBLE : View.GONE); } });
            } catch(Exception error) { message(error.getMessage()); }
        });
    }
    @Override public void open(QuickAction action) { startActivity(new Intent(requireContext(),ActionEditorActivity.class).putExtra("action_id",action.id)); }
    @Override public void run(QuickAction action) { startActivity(ActionStore.launchIntent(requireContext(),action.id)); }
    @Override public void options(QuickAction action) {
        AppDialogs.builder(requireContext()).setTitle(action.name).setItems(new String[]{"编辑动作","执行动作","创建桌面快捷方式","删除动作"},(dialog,index) -> {
            if(index == 0) open(action); else if(index == 1) run(action); else if(index == 2) pin(action);
            else AppDialogs.builder(requireContext()).setTitle("删除动作？").setMessage("关联的桌面快捷方式将失效，卡片中将不再显示此动作。收藏和捕获记录会保留。")
                    .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete,(confirm,which) -> {
                        Context context=requireContext().getApplicationContext();
                        ActionStore.IO.execute(() -> { try { ActionStore.get(context).delete(action.id); main.post(() -> { if(isAdded() && getView() != null) reload(); }); }
                            catch(Exception error) { message(error.getMessage()); } });
                    }).show();
        }).show();
    }
    @Override public void reordered(List<String> ids) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> { try { ActionStore.get(context).reorder(ids); } catch(Exception error) { message(error.getMessage()); } });
    }
    private void pin(QuickAction action) {
        if(Build.VERSION.SDK_INT < 26) { message("当前系统不支持请求固定快捷方式"); return; }
        ShortcutManager manager=requireContext().getSystemService(ShortcutManager.class);
        if(manager == null || !manager.isRequestPinShortcutSupported()) { message("当前桌面不支持固定快捷方式"); return; }
        if(ShortcutHelper.checkShortcutPermissionBestEffort(requireContext()) == ShortcutHelper.PermissionState.DENIED) {
            AppDialogs.builder(requireContext()).setTitle("需要桌面快捷方式权限").setMessage("请在系统设置中允许创建桌面快捷方式后重试。")
                    .setNegativeButton(R.string.cancel,null).setPositiveButton("去设置",(d,w) -> ShortcutHelper.openShortcutPermissionSettings(requireContext())).show(); return;
        }
        try {
            Icon icon=action.icon == null ? Icon.createWithResource(requireContext(),R.mipmap.ic_launcher)
                    : Icon.createWithBitmap(BitmapFactory.decodeByteArray(action.icon,0,action.icon.length));
            boolean sent=manager.requestPinShortcut(new ShortcutInfo.Builder(requireContext(),"action-"+action.id)
                    .setShortLabel(action.name).setLongLabel(action.name).setIcon(icon).setIntent(ActionStore.launchIntent(requireContext(),action.id)).build(),null);
            message(sent ? "请在桌面弹窗中确认添加；未出现时请检查桌面权限" : "桌面未接受添加请求");
        } catch(Exception error) { message(error.getMessage()); }
    }
    private void pinWidget() {
        if(Build.VERSION.SDK_INT < 26) { message("请长按桌面，进入小组件列表添加 JumpReplay 叠卡"); return; }
        AppWidgetManager manager=AppWidgetManager.getInstance(requireContext());
        if(!manager.isRequestPinAppWidgetSupported()) { message("请长按桌面，进入小组件列表添加 JumpReplay 叠卡"); return; }
        try {
            Intent configure=new Intent(requireContext(),com.fourtwo.hookintent.widget.ActionWidgetConfigureActivity.class)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent callback=android.app.PendingIntent.getActivity(requireContext(),1700,configure,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0));
            boolean sent=manager.requestPinAppWidget(new ComponentName(requireContext(),ActionWidgetProvider.class),null,callback);
            message(sent ? "请在桌面确认添加，并选择要显示的动作" : "桌面未接受添加请求，请从小组件列表添加");
        } catch(Exception error) { message(error.getMessage()); }
    }
    private void writeBackup(Uri uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try(OutputStream output=context.getContentResolver().openOutputStream(uri,"wt")) {
                if(output == null) throw new IOException("无法写入所选文件");
                ActionStore.get(context).exportTo(output);
                message("动作备份已导出");
            } catch(Exception | OutOfMemoryError error) { message("导出失败："+error.getMessage()); }
        });
    }
    private void readBackup(Uri uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input == null) throw new IOException("无法读取所选文件");
                PushbackInputStream stream=new PushbackInputStream(input,1); int first;
                do { first=stream.read(); } while(first != -1 && Character.isWhitespace(first));
                if(first != -1) stream.unread(first);
                if(first != '{') {
                    Reader reader=new InputStreamReader(stream,StandardCharsets.UTF_8); StringBuilder textBuilder=new StringBuilder(); char[] buffer=new char[8192]; int length;
                    while((length=reader.read(buffer)) != -1) textBuilder.append(buffer,0,length);
                    String text=textBuilder.toString().trim();
                    new QuickAction("draft","draft",text,new String[0],false,"root",null,null).validate();
                    main.post(() -> { if(isAdded()) openUriEditor(text); });
                } else {
                    int added=ActionStore.get(context).importFrom(stream); message("已导入 "+added+" 个动作");
                    main.post(() -> { if(isAdded() && getView() != null) reload(); });
                }
            } catch(Exception | OutOfMemoryError error) { message("导入失败："+error.getMessage()); }
        });
    }
    private void openUriEditor(String uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try {
                String id=ShortcutPayloadStore.save(context,uri,new String[0],null);
                main.post(() -> { if(isAdded()) startActivity(new Intent(requireContext(),ActionEditorActivity.class).putExtra("draft_payload_id",id)); });
            } catch(Exception error) { message(error.getMessage()); }
        });
    }
    private void message(String text) { main.post(() -> { if(isAdded()) Toast.makeText(requireContext(),text,Toast.LENGTH_LONG).show(); }); }
    @Override public void onDestroyView() { generation++; adapter=null; empty=null; super.onDestroyView(); }
}
