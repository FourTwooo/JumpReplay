package com.fourtwo.hookintent.actions;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.*;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.utils.*;
import com.fourtwo.hookintent.replay.*;
import org.json.JSONObject;
import java.util.*;
import java.io.*;

public class ActionEditorActivity extends AppCompatActivity {
    private EditText name,template;
    private LinearLayout parameters;
    private ImageView icon;
    private CheckBox privileged;
    private Spinner mode;
    private Button save;
    private String id,payload;
    private String draftId;
    private byte[] iconBytes;
    private boolean loaded,saving;
    private ExtrasClient merge;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> imagePicker=registerForActivityResult(new ActivityResultContracts.GetContent(),uri -> {
        if(uri == null) return;
        ActionStore.IO.execute(() -> {
            try {
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
                try(InputStream input=getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input,null,options); }
                options.inSampleSize=1;
                while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize > 512) options.inSampleSize*=2;
                options.inJustDecodeBounds=false;
                Bitmap bitmap;
                try(InputStream input=getContentResolver().openInputStream(uri)) { bitmap=BitmapFactory.decodeStream(input,null,options); }
                if(bitmap == null) throw new IOException(getString(R.string.action_image_read_error));
                byte[] bytes=iconData(bitmap);
                main.post(() -> { if(alive()) { iconBytes=bytes; showIcon(); } });
            } catch(Exception | OutOfMemoryError error) { error(error); }
        });
    });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_action_editor);
        Toolbar toolbar=findViewById(R.id.action_toolbar);
        toolbar.setTitle(R.string.action_edit); toolbar.setNavigationIcon(R.drawable.action_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.getMenu().add(0,1,0,R.string.extras_editor_title);
        toolbar.setOnMenuItemClickListener(item -> { editExtras(); return true; });
        ((DialogScrollView)findViewById(R.id.shortcut_form)).setFullHeight(true);
        name=findViewById(R.id.shortcut_name); template=findViewById(R.id.intent_string);
        ((com.google.android.material.textfield.TextInputLayout)findViewById(R.id.shortcut_name_box)).setHint(getString(R.string.action_name));
        ((com.google.android.material.textfield.TextInputLayout)findViewById(R.id.shortcut_content_box)).setHint(getString(R.string.action_content));
        parameters=findViewById(R.id.dynamic_params_container); icon=findViewById(R.id.icon_preview);
        privileged=findViewById(R.id.enable_feature_checkbox); mode=findViewById(R.id.dropdown_spinner);
        save=findViewById(R.id.submit_button); save.setText(R.string.action_save); save.setEnabled(false);
        findViewById(R.id.cancel_button).setOnClickListener(v -> finish());
        findViewById(R.id.add_param_button).setOnClickListener(v -> addParameter(""));
        TextView help=new TextView(this); help.setText(R.string.action_parameters_hint);
        help.setTextColor(androidx.core.content.ContextCompat.getColor(this,R.color.screen_secondary)); help.setTextSize(12);
        parameters.addView(help);
        icon.setContentDescription(getString(R.string.action_choose_icon));
        icon.setOnClickListener(v -> chooseIcon()); save.setOnClickListener(v -> save());
        id=state == null ? getIntent().getStringExtra("action_id") : state.getString("action_id");
        if(id == null) id=UUID.randomUUID().toString();
        String restore=state == null ? null : state.getString("editor_state_id");
        String current=state == null ? null : state.getString("action_draft_id");
        draftId=current!=null ? current : getIntent().getStringExtra("action_draft_id");
        if(draftId==null) draftId=ActionDraftStore.newId();
        ActionDraftStore.retain(draftId);
        boolean restoreDraft=state!=null && state.getBoolean("editor_form_saved",false);
        ActionStore.IO.execute(() -> {
            try {
                ActionDraftStore.prune(this,System.currentTimeMillis());
                QuickAction action=null;
                if(restoreDraft) action=QuickAction.fromJson(ActionDraftStore.read(this,draftId),false);
                else if(restore != null) action=QuickAction.fromJson(ShortcutPayloadStore.readRaw(this,restore),false);
                else if(getIntent().hasExtra("action_id")) {
                    action=ActionStore.get(this).find(id);
                    if(action == null) throw new IllegalArgumentException(getString(R.string.action_missing));
                } else if(getIntent().hasExtra("action_draft_id")) {
                    JSONObject draft=ActionDraftStore.read(this,draftId);
                    action=new QuickAction(id,getIntent().getStringExtra("draft_name"),draft.getString("template"),new String[0],false,"root",draft.toString(),null);
                } else if(getIntent().hasExtra("draft_payload_id")) {
                    JSONObject draft=ShortcutPayloadStore.readRaw(this,getIntent().getStringExtra("draft_payload_id"));
                    action=new QuickAction(id,getIntent().getStringExtra("draft_name"),draft.getString("template"),new String[0],false,"root",draft.toString(),null);
                } else if(getIntent().hasExtra(DetailPayloadStore.EXTRA_TOKEN)) {
                    ItemData item=DetailPayloadStore.load(this,getIntent().getStringExtra(DetailPayloadStore.EXTRA_TOKEN));
                    Bundle origin=item.getAppBundle();
                    Intent target="Intent".equals(item.getCategory()) ? IntentPayloadCodec.decode(this,origin) : null;
                    String uri="Scheme".equals(item.getCategory()) ? origin.getString("scheme_raw_url",item.getUri()) : item.getUri();
                    JSONObject content=ShortcutPayloadStore.content(uri,new String[0],target,origin);
                    action=new QuickAction(id,item.getAppName(),uri,new String[0],false,"root",content.toString(),drawableData(item.getIcon()));
                }
                QuickAction value=action;
                main.post(() -> {
                    if(!alive()) return;
                    if(value != null) render(value);
                    else template.setText(getIntent().getStringExtra("initial_uri"));
                    loaded=true; save.setEnabled(true);
                });
            } catch(Exception error) { error(error); main.post(this::finish); }
        });
    }
    private void render(QuickAction value) {
        name.setText(value.name); template.setText(value.template); privileged.setChecked(value.privileged);
        for(int i=0;i<mode.getCount();i++) if(QuickAction.modeAt(i).equals(value.mode)) mode.setSelection(i);
        payload=value.payload; iconBytes=value.icon; showIcon();
        for(String parameter:value.parameters) addParameter(parameter);
    }
    private void showIcon() {
        if(iconBytes == null) icon.setImageResource(R.mipmap.ic_launcher);
        else icon.setImageBitmap(BitmapFactory.decodeByteArray(iconBytes,0,iconBytes.length));
    }
    private void addParameter(String text) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input=AppDialogs.input(this); input.setHint(getString(R.string.action_parameter_original)); input.setSingleLine(); input.setText(text);
        row.addView(input,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        ImageButton remove=new ImageButton(this); remove.setImageResource(R.drawable.baseline_delete_outline_24);
        remove.setBackground(null); remove.setContentDescription(getString(R.string.delete));
        row.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(48))); remove.setOnClickListener(v -> parameters.removeView(row));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.topMargin=dp(10); parameters.addView(row,layout);
    }
    private QuickAction readForm() {
        List<String> names=new ArrayList<>();
        for(int i=0;i<parameters.getChildCount();i++) if(parameters.getChildAt(i) instanceof LinearLayout) {
            LinearLayout row=(LinearLayout)parameters.getChildAt(i); names.add(((EditText)row.getChildAt(0)).getText().toString());
        }
        return new QuickAction(id,name.getText().toString().trim(),template.getText().toString().trim(),names.toArray(new String[0]),
                privileged.isChecked(),QuickAction.modeAt(mode.getSelectedItemPosition()),payload,iconBytes);
    }
    private void save() {
        if(!loaded || saving) return;
        final QuickAction action=readForm();
        try { action.validate(); } catch(Exception error) { template.setError(ActionValidationException.text(this,error)); return; }
        saving=true; save.setEnabled(false);
        ActionStore.IO.execute(() -> {
            try {
                QuickAction complete=action;
                if(action.payload == null) complete=new QuickAction(action.id,action.name,action.template,action.parameters,action.privileged,action.mode,
                        ShortcutPayloadStore.content(action.template,action.parameters,Intent.parseUri(action.template,0),null).toString(),action.icon);
                ActionStore.get(this).save(complete);
                main.post(() -> { if(alive()) { Toast.makeText(this,R.string.action_saved,Toast.LENGTH_SHORT).show(); setResult(RESULT_OK); finish(); } });
            } catch(Exception error) { error(error); main.post(() -> { saving=false; if(alive()) save.setEnabled(true); }); }
        });
    }
    private void editExtras() {
        if(!loaded || saving) return;
        try {
            String text=template.getText().toString().trim();
            ShortcutPayloadStore.Payload snapshot=payload == null ? null : ShortcutPayloadStore.decode(this,new JSONObject(payload));
            Bundle origin=snapshot == null ? new Bundle() : snapshot.origin;
            if(merge != null) merge.close();
            merge=ReplayTasks.fromText(this,origin,text,snapshot == null ? null : snapshot.target,
                    snapshot == null ? text : snapshot.template,(target,error) -> {
                if(!alive()) return;
                if(error != null) { error(error); return; }
                try {
                    Bundle record=new Bundle(origin); record.putString(IntentPayloadCodec.KEY,IntentPayloadCodec.encode(target)); record.putString("uri",text);
                    ExtrasEditorDialog.show(this,record,edited -> {
                        if(!alive()) return;
                        try {
                            String uri=edited.getString("uri",text);
                            payload=ShortcutPayloadStore.content(uri,new String[0],IntentPayloadCodec.decode(this,edited),edited).toString();
                            template.setText(uri);
                        } catch(Exception failure) { throw new IllegalStateException(failure); }
                    });
                } catch(Exception failure) { error(failure); }
            });
        } catch(Exception error) { error(error); }
    }
    private void chooseIcon() {
        AppDialogs.builder(this).setTitle(R.string.action_choose_icon).setItems(R.array.action_icon_options,(dialog,which) -> {
            if(which == 1) imagePicker.launch("image/*");
            else if(which == 2) { iconBytes=null; showIcon(); }
            else ActionStore.IO.execute(() -> {
                try {
                    List<android.content.pm.ApplicationInfo> apps=getPackageManager().getInstalledApplications(0);
                    apps.removeIf(info -> getPackageManager().getLaunchIntentForPackage(info.packageName) == null);
                    apps.sort(Comparator.comparing(info -> getPackageManager().getApplicationLabel(info).toString(),java.text.Collator.getInstance()));
                    String[] labels=new String[apps.size()];
                    for(int i=0;i<apps.size();i++) labels[i]=getPackageManager().getApplicationLabel(apps.get(i))+"\n"+apps.get(i).packageName;
                    main.post(() -> {
                        if(!alive()) return;
                        androidx.appcompat.app.AlertDialog picker=AppDialogs.builder(this).setTitle(R.string.action_choose_icon)
                                .setItems(labels,(selected,index) -> {
                                    try { iconBytes=drawableData(apps.get(index).loadIcon(getPackageManager())); showIcon(); }
                                    catch(Exception error) { error(error); }
                                }).setNegativeButton(R.string.cancel,null).create();
                        picker.show(); AppDialogs.sizeForList(picker);
                    });
                } catch(Exception error) { error(error); }
            });
        }).show();
    }
    private byte[] drawableData(Drawable drawable) throws IOException {
        if(drawable == null) return null;
        Bitmap image=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(image);
        drawable.setBounds(0,0,192,192); drawable.draw(canvas); return iconData(image);
    }
    private byte[] iconData(Bitmap source) throws IOException {
        float ratio=Math.min(1f,192f/Math.max(source.getWidth(),source.getHeight()));
        Bitmap bitmap=Bitmap.createScaledBitmap(source,Math.max(1,Math.round(source.getWidth()*ratio)),Math.max(1,Math.round(source.getHeight()*ratio)),true);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new IOException(getString(R.string.action_icon_save_error)); return output.toByteArray();
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private int dp(int value) { return AppDialogs.dp(this,value); }
    private void error(Throwable error) { main.post(() -> { if(alive()) Toast.makeText(this,ActionValidationException.text(this,error),Toast.LENGTH_LONG).show(); }); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("action_id",id);
        state.putString("action_draft_id",draftId);
        if(loaded) try { ActionDraftStore.write(this,draftId,readForm().json());state.putBoolean("editor_form_saved",true); }
        catch(Exception error) { error(error); }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if(merge != null) merge.close();
        String cleanup=draftId;ActionDraftStore.release(cleanup);
        if(isFinishing()) ActionStore.IO.execute(() -> ActionDraftStore.delete(getApplicationContext(),cleanup));
        super.onDestroy();
    }
}
