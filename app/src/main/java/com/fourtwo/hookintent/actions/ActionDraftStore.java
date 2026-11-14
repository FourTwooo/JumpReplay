package com.fourtwo.hookintent.actions;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Only disposable editor data lives here. Permanent shortcut payloads are never scanned. */
public final class ActionDraftStore {
    static final long RETENTION_MS=7L*24*60*60*1000;
    private static final Map<String,Integer> active=new HashMap<>();
    private ActionDraftStore() { }
    public static String newId() { return UUID.randomUUID().toString(); }
    public static synchronized void retain(String id) { active.put(id,active.getOrDefault(id,0)+1); }
    public static synchronized void release(String id) {
        if(id==null) return;
        int remaining=active.getOrDefault(id,0)-1;
        if(remaining<=0) active.remove(id);else active.put(id,remaining);
    }
    private static File file(Context context,String id) {
        if(id==null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid action draft identifier");
        return new File(new File(context.getFilesDir(),"action_drafts"),id+".json");
    }
    public static String create(Context context,JSONObject content) throws IOException {
        String id=newId();write(context,id,content);return id;
    }
    public static synchronized void write(Context context,String id,JSONObject content) throws IOException {
        File target=file(context,id);
        if(!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create action draft directory");
        AtomicFile atomic=new AtomicFile(target);FileOutputStream output=null;
        try {
            output=atomic.startWrite();output.write(content.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output);
        } catch(IOException error) { atomic.failWrite(output);throw error; }
    }
    public static synchronized JSONObject read(Context context,String id) throws Exception {
        return new JSONObject(new String(new AtomicFile(file(context,id)).readFully(),StandardCharsets.UTF_8));
    }
    public static synchronized void delete(Context context,String id) {
        if(id!=null) new AtomicFile(file(context,id)).delete();
    }
    public static synchronized void prune(Context context,long now) {
        File folder=new File(context.getFilesDir(),"action_drafts");File[] files=folder.listFiles();
        if(files==null) return;
        for(File candidate:files) {
            String name=candidate.getName();
            if(!name.matches("[0-9a-fA-F-]{36}\\.json(?:\\.bak|\\.new)?")) continue;
            String id=name.substring(0,36);
            if(!active.containsKey(id) && candidate.lastModified()<now-RETENTION_MS) delete(context,id);
        }
    }
}
