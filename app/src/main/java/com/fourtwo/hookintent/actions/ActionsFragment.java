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
                            .setItems(R.array.action_import_options,(dialog,index) -> {
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
                .setView(content).setNegativeButton(R.string.cancel,null).setPositiveButton(getString(R.string.action_next),null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(button -> {
            String uri=input.getText().toString().trim();
            try {
                new QuickAction("draft","draft",uri,new String[0],false,"root",null,null).validate();
                openUriEditor(uri); dialog.dismiss();
            } catch(Exception error) { input.setError(ActionValidationException.text(requireContext(),error)); }
        })); dialog.show();
    }
    @Override public void onResume() { super.onResume(); reload(); }
    private void reload() {
        int request=++generation; Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try {
                List<QuickAction> values=ActionStore.get(context).all();
                main.post(() -> { if(getView() != null && generation == request) { adapter.submit(values,null); empty.setVisibility(values.isEmpty() ? View.VISIBLE : View.GONE); } });
            } catch(Exception error) { failure(error); }
        });
    }
    @Override public void open(QuickAction action) { startActivity(new Intent(requireContext(),ActionEditorActivity.class).putExtra("action_id",action.id)); }
    @Override public void run(QuickAction action) { startActivity(ActionStore.launchIntent(requireContext(),action.id)); }
    @Override public void options(QuickAction action) {
        AppDialogs.builder(requireContext()).setTitle(action.name).setItems(R.array.action_menu_options,(dialog,index) -> {
            if(index == 0) open(action); else if(index == 1) run(action); else if(index == 2) pin(action);
            else AppDialogs.builder(requireContext()).setTitle(getString(R.string.action_delete_title)).setMessage(getString(R.string.action_delete_body))
                    .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete,(confirm,which) -> {
                        Context context=requireContext().getApplicationContext();
                        ActionStore.IO.execute(() -> { try { ActionStore.get(context).delete(action.id); main.post(() -> { if(isAdded() && getView() != null) reload(); }); }
                            catch(Exception error) { failure(error); } });
                    }).show();
        }).show();
    }
    @Override public void reordered(List<String> ids) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> { try { ActionStore.get(context).reorder(ids); } catch(Exception error) { failure(error); } });
    }
    private void pin(QuickAction action) {
        if(Build.VERSION.SDK_INT < 26) { message(getString(R.string.action_pin_system_unsupported)); return; }
        ShortcutManager manager=requireContext().getSystemService(ShortcutManager.class);
        if(manager == null || !manager.isRequestPinShortcutSupported()) { message(getString(R.string.action_pin_launcher_unsupported)); return; }
        if(ShortcutHelper.checkShortcutPermissionBestEffort(requireContext()) == ShortcutHelper.PermissionState.DENIED) {
            AppDialogs.builder(requireContext()).setTitle(getString(R.string.action_pin_permission_title)).setMessage(getString(R.string.action_pin_permission_body))
                    .setNegativeButton(R.string.cancel,null).setPositiveButton(getString(R.string.action_open_settings),(d,w) -> ShortcutHelper.openShortcutPermissionSettings(requireContext())).show(); return;
        }
        try {
            Icon icon=action.icon == null ? Icon.createWithResource(requireContext(),R.mipmap.ic_launcher)
                    : Icon.createWithBitmap(BitmapFactory.decodeByteArray(action.icon,0,action.icon.length));
            boolean sent=manager.requestPinShortcut(new ShortcutInfo.Builder(requireContext(),"action-"+action.id)
                    .setShortLabel(action.name).setLongLabel(action.name).setIcon(icon).setIntent(ActionStore.launchIntent(requireContext(),action.id)).build(),null);
            message(sent ? getString(R.string.action_pin_confirm) : getString(R.string.action_pin_rejected));
        } catch(Exception error) { failure(error); }
    }
    private void pinWidget() {
        if(Build.VERSION.SDK_INT < 26) { message(getString(R.string.action_widget_manual)); return; }
        AppWidgetManager manager=AppWidgetManager.getInstance(requireContext());
        if(!manager.isRequestPinAppWidgetSupported()) { message(getString(R.string.action_widget_manual)); return; }
        try {
            Intent configure=new Intent(requireContext(),com.fourtwo.hookintent.widget.ActionWidgetConfigureActivity.class)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent callback=android.app.PendingIntent.getActivity(requireContext(),1700,configure,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0));
            boolean sent=manager.requestPinAppWidget(new ComponentName(requireContext(),ActionWidgetProvider.class),null,callback);
            message(sent ? getString(R.string.action_widget_confirm) : getString(R.string.action_widget_rejected));
        } catch(Exception error) { failure(error); }
    }
    private void writeBackup(Uri uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try(OutputStream output=context.getContentResolver().openOutputStream(uri,"wt")) {
                if(output == null) throw new IOException(context.getString(R.string.action_file_write_error));
                ActionStore.get(context).exportTo(output);
                message(R.string.action_backup_exported);
            } catch(Exception | OutOfMemoryError error) { failure(R.string.action_backup_export_error,error); }
        });
    }
    private void readBackup(Uri uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input == null) throw new IOException(context.getString(R.string.action_file_read_error));
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
                    int added=ActionStore.get(context).importFrom(stream); message(R.string.action_import_count,added);
                    main.post(() -> { if(isAdded() && getView() != null) reload(); });
                }
            } catch(Exception | OutOfMemoryError error) { failure(R.string.action_backup_import_error,error); }
        });
    }
    private void openUriEditor(String uri) {
        Context context=requireContext().getApplicationContext();
        ActionStore.IO.execute(() -> {
            try {
                String id=ActionDraftStore.create(context,ShortcutPayloadStore.content(uri,new String[0],null,null));
                main.post(() -> {
                    if(isAdded()) startActivity(new Intent(requireContext(),ActionEditorActivity.class).putExtra("action_draft_id",id));
                    else ActionStore.IO.execute(() -> ActionDraftStore.delete(context,id));
                });
            } catch(Exception error) { failure(error); }
        });
    }
    private void message(int resource,Object... arguments) { main.post(() -> { if(isAdded()) Toast.makeText(requireContext(),getString(resource,arguments),Toast.LENGTH_LONG).show(); }); }
    private void failure(int prefix,Throwable error) { main.post(() -> { if(isAdded()) message(prefix,ActionValidationException.text(requireContext(),error)); }); }
    private void failure(Throwable error) { main.post(() -> { if(isAdded()) message(ActionValidationException.text(requireContext(),error)); }); }
    private void message(String text) { main.post(() -> { if(isAdded()) Toast.makeText(requireContext(),text,Toast.LENGTH_LONG).show(); }); }
    @Override public void onDestroyView() { generation++; adapter=null; empty=null; super.onDestroyView(); }
}
