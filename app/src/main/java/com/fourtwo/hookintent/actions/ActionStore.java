package com.fourtwo.hookintent.actions;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import com.fourtwo.hookintent.ShortcutActivity;
import com.fourtwo.hookintent.widget.ActionWidgetProvider;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** SQLite transactions also make changes visible to the existing :shortcut process. */
public final class ActionStore extends SQLiteOpenHelper {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile ActionStore instance;
    private final Context context;
    public static ActionStore get(Context context) {
        if(instance == null) synchronized(ActionStore.class) {
            if(instance == null) instance=new ActionStore(context.getApplicationContext());
        }
        return instance;
    }
    private ActionStore(Context context) { super(context,"quick_actions.db",null,1); this.context=context; }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE actions (id TEXT PRIMARY KEY, content TEXT NOT NULL, position INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE payloads (action_id TEXT NOT NULL, part INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(action_id,part))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    public List<QuickAction> all() throws Exception {
        List<QuickAction> result=new ArrayList<>();
        try(Cursor cursor=getReadableDatabase().query("actions",new String[]{"id","content"},null,null,null,null,"position ASC, rowid ASC")) {
            while(cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }
    private QuickAction read(Cursor cursor) throws Exception {
        QuickAction value=QuickAction.fromJson(new JSONObject(cursor.getString(1)),false);
        return new QuickAction(cursor.getString(0),value.name,value.template,value.parameters,value.privileged,value.mode,value.payload,value.icon);
    }
    public QuickAction find(String id) throws Exception {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransactionNonExclusive();
        try(Cursor cursor=db.query("actions",new String[]{"id","content"},"id=?",new String[]{id},null,null,null)) {
            if(!cursor.moveToFirst()) return null;
            QuickAction metadata=read(cursor); StringBuilder snapshot=new StringBuilder();
            try(Cursor parts=db.query("payloads",new String[]{"value"},"action_id=?",new String[]{id},null,null,"part ASC")) {
                while(parts.moveToNext()) snapshot.append(parts.getString(0));
            }
            db.setTransactionSuccessful();
            if(snapshot.toString().startsWith("action-v1\n")) {
                QuickAction value=QuickAction.fromJson(new JSONObject(snapshot.substring("action-v1\n".length())));
                return new QuickAction(id,value.name,value.template,value.parameters,value.privileged,value.mode,value.payload,value.icon);
            }
            return new QuickAction(metadata.id,metadata.name,metadata.template,metadata.parameters,metadata.privileged,
                    metadata.mode,snapshot.length() == 0 ? null : snapshot.toString(),metadata.icon);
        } finally { db.endTransaction(); }
    }
    public void save(QuickAction value) throws Exception {
        value.validate(); SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { write(db,value,nextPosition(db)); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        changed();
    }
    private void write(SQLiteDatabase db,QuickAction value,long position) throws Exception {
        JSONObject complete=value.json();
        String full="action-v1\n"+complete.toString();
        JSONObject metadata=new JSONObject();
        java.util.Iterator<String> keys=complete.keys();
        while(keys.hasNext()) { String key=keys.next(); if(!"payload".equals(key) && !"template".equals(key)) metadata.put(key,complete.get(key)); }
        metadata.put("template",value.template.substring(0,safeEnd(value.template,512)));
        ContentValues fields=new ContentValues(); fields.put("content",metadata.toString());
        if(db.update("actions",fields,"id=?",new String[]{value.id}) == 0) {
            fields.put("id",value.id); fields.put("position",position); db.insertOrThrow("actions",null,fields);
        }
        db.delete("payloads","action_id=?",new String[]{value.id});
        for(int start=0, part=0;start<full.length();part++) {
            int end=safeEnd(full,start+128*1024);
            ContentValues chunk=new ContentValues(); chunk.put("action_id",value.id); chunk.put("part",part);
            chunk.put("value",full.substring(start,end)); start=end;
            db.insertOrThrow("payloads",null,chunk);
        }
    }
    private int safeEnd(String text,int limit) {
        int end=Math.min(text.length(),limit);
        if(end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }
    private long nextPosition(SQLiteDatabase db) {
        try(Cursor cursor=db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM actions",null)) { cursor.moveToFirst(); return cursor.getLong(0); }
    }
    public void delete(String id) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try { db.delete("actions","id=?",new String[]{id}); db.delete("payloads","action_id=?",new String[]{id}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        changed();
    }
    public void reorder(List<String> ids) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            for(int i=0;i<ids.size();i++) { ContentValues value=new ContentValues(); value.put("position",i); db.update("actions",value,"id=?",new String[]{ids.get(i)}); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed();
    }
    public JSONObject exportData() throws Exception {
        JSONArray entries=new JSONArray(); for(QuickAction value:all()) { QuickAction full=find(value.id); if(full != null) entries.put(full.json()); }
        return new JSONObject().put("format","jumpreplay-actions").put("version",1).put("actions",entries);
    }
    public void exportTo(java.io.OutputStream output) throws Exception {
        java.nio.charset.Charset utf8=java.nio.charset.StandardCharsets.UTF_8;
        output.write("{\"format\":\"jumpreplay-actions\",\"version\":1,\"actions\":[".getBytes(utf8));
        boolean first=true;
        for(QuickAction metadata:all()) {
            QuickAction full=find(metadata.id); if(full == null) continue;
            if(!first) output.write(','); first=false;
            output.write(full.json().toString().getBytes(utf8));
        }
        output.write("]}".getBytes(utf8)); output.flush();
    }
    public int importFrom(java.io.InputStream input) throws Exception {
        android.util.JsonReader reader=new android.util.JsonReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        int count=0;
        try {
            long position=nextPosition(db); String format=null; int version=-1; boolean entries=false;
            reader.beginObject();
            while(reader.hasNext()) {
                String key=reader.nextName();
                if("format".equals(key)) format=reader.nextString();
                else if("version".equals(key)) version=reader.nextInt();
                else if("actions".equals(key)) {
                    if(entries) throw new IllegalArgumentException("备份包含重复的动作列表");
                    entries=true; reader.beginArray();
                    while(reader.hasNext()) {
                        Object value=readJson(reader,0);
                        if(!(value instanceof JSONObject)) throw new IllegalArgumentException("动作条目无效");
                        write(db,QuickAction.fromJson((JSONObject)value),position++); count++;
                    }
                    reader.endArray();
                } else reader.skipValue();
            }
            reader.endObject();
            if(!entries || !"jumpreplay-actions".equals(format) || version != 1
                    || reader.peek() != android.util.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("不是支持的 JumpReplay 动作备份");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed(); return count;
    }
    private Object readJson(android.util.JsonReader reader,int depth) throws Exception {
        if(depth > 64) throw new IllegalArgumentException("备份结构嵌套过深");
        switch(reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object=new JSONObject(); reader.beginObject();
                while(reader.hasNext()) object.put(reader.nextName(),readJson(reader,depth+1)); reader.endObject(); return object;
            case BEGIN_ARRAY:
                JSONArray array=new JSONArray(); reader.beginArray();
                while(reader.hasNext()) array.put(readJson(reader,depth+1)); reader.endArray(); return array;
            case STRING: return reader.nextString();
            case NUMBER:
                String number=reader.nextString();
                try {
                    long integer=Long.parseLong(number);
                    if(integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE) return Integer.valueOf((int)integer);
                    return Long.valueOf(integer);
                } catch(NumberFormatException ignored) { return Double.valueOf(number); }
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new IllegalArgumentException("备份 JSON 无效");
        }
    }
    public int importData(JSONObject data) throws Exception {
        if(!"jumpreplay-actions".equals(data.optString("format")) || data.optInt("version") != 1)
            throw new IllegalArgumentException("不是支持的 JumpReplay 动作备份");
        JSONArray entries=data.getJSONArray("actions"); List<QuickAction> actions=new ArrayList<>();
        for(int i=0;i<entries.length();i++) actions.add(QuickAction.fromJson(entries.getJSONObject(i)));
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            long position=nextPosition(db);
            for(QuickAction action:actions) {
                write(db,action,position++);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed(); return actions.size();
    }
    public static Intent launchIntent(Context context,String id) {
        return new Intent(context,ShortcutActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("jumpreplay-action:"+id)).putExtra("quick_action_id",id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private void changed() {
        MAIN.post(() -> ActionWidgetProvider.updateAll(context));
        IO.execute(this::refreshPinnedShortcuts);
    }
    private void refreshPinnedShortcuts() {
        if(android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            android.content.pm.ShortcutManager manager=context.getSystemService(android.content.pm.ShortcutManager.class);
            if(manager == null) return;
            Map<String,QuickAction> actions=new HashMap<>(); for(QuickAction action:all()) actions.put("action-"+action.id,action);
            List<android.content.pm.ShortcutInfo> updates=new ArrayList<>(); List<String> deleted=new ArrayList<>();
            for(android.content.pm.ShortcutInfo pinned:manager.getPinnedShortcuts()) {
                if(!pinned.getId().startsWith("action-")) continue;
                QuickAction action=actions.get(pinned.getId());
                if(action == null) { deleted.add(pinned.getId()); continue; }
                android.graphics.drawable.Icon icon=action.icon == null ? android.graphics.drawable.Icon.createWithResource(context,com.fourtwo.hookintent.R.mipmap.ic_launcher)
                        : android.graphics.drawable.Icon.createWithBitmap(android.graphics.BitmapFactory.decodeByteArray(action.icon,0,action.icon.length));
                updates.add(new android.content.pm.ShortcutInfo.Builder(context,pinned.getId()).setShortLabel(action.name).setLongLabel(action.name)
                        .setIcon(icon).setIntent(launchIntent(context,action.id)).build());
            }
            if(!updates.isEmpty()) manager.updateShortcuts(updates);
            if(!deleted.isEmpty()) manager.disableShortcuts(deleted,"该快捷动作已删除，请重新添加");
        } catch(Exception error) { android.util.Log.w("QuickActions","Cannot refresh pinned shortcuts",error); }
    }
}
