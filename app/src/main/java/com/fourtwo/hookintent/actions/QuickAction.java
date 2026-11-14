package com.fourtwo.hookintent.actions;

import com.fourtwo.hookintent.R;
import org.json.JSONArray;
import org.json.JSONObject;
import com.fourtwo.hookintent.utils.ShortcutParameterReplacer;

/** Immutable snapshot. Captures/favourites are never referenced as mutable dependencies. */
public final class QuickAction {
    private static final String[] MODES={"root","Shizuku","Shizuku - 系统助手"};
    public static String modeAt(int position) { return position>=0 && position<MODES.length ? MODES[position] : ""; }
    private static String canonicalMode(String mode) {
        return com.fourtwo.hookintent.utils.ShortcutLaunchCoordinator.canonicalSelection(mode);
    }
    public final String id, name, template, mode, payload;
    public final String[] parameters;
    public final boolean privileged;
    public final byte[] icon;
    public QuickAction(String id, String name, String template, String[] parameters, boolean privileged,
                       String mode, String payload, byte[] icon) {
        this.id=id; this.name=name; this.template=template; this.parameters=parameters.clone();
        this.privileged=privileged; this.mode=canonicalMode(mode); this.payload=payload; this.icon=icon == null ? null : icon.clone();
    }
    public void validate() throws Exception {
        if (name == null || name.trim().isEmpty()) throw new ActionValidationException(R.string.action_error_name);
        ShortcutParameterReplacer.validate(template,parameters);
        android.content.Intent.parseUri(template,0);
        if (!template.contains("#Intent;") && android.net.Uri.parse(template).getScheme() == null)
            throw new ActionValidationException(R.string.action_error_uri);
        if (privileged && !java.util.Arrays.asList(MODES).contains(mode))
            throw new ActionValidationException(R.string.action_error_mode);
        if (payload != null) {
            JSONObject snapshot = new JSONObject(payload);
            if (!snapshot.has("template")) throw new ActionValidationException(R.string.action_error_snapshot);
        }
        if (icon != null && icon.length > 512 * 1024) throw new ActionValidationException(R.string.action_error_icon_size);
        if(icon != null) {
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeByteArray(icon,0,icon.length,bounds);
            if(bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 512 || bounds.outHeight > 512)
                throw new ActionValidationException(R.string.action_error_icon);
        }
    }
    public JSONObject json() throws Exception {
        JSONObject value=new JSONObject();
        value.put("name",name); value.put("template",template); value.put("parameters",new JSONArray(parameters));
        value.put("privileged",privileged); value.put("mode",mode);
        if (payload != null) value.put("payload",new JSONObject(payload));
        if (icon != null) value.put("icon",android.util.Base64.encodeToString(icon,android.util.Base64.NO_WRAP));
        return value;
    }
    public static QuickAction fromJson(JSONObject value) throws Exception {
        return fromJson(value,true);
    }
    public static QuickAction fromJson(JSONObject value,boolean validate) throws Exception {
        JSONArray names=value.optJSONArray("parameters");
        String[] parameters=new String[names == null ? 0 : names.length()];
        for(int i=0;i<parameters.length;i++) parameters[i]=names.getString(i);
        JSONObject payload=value.optJSONObject("payload");
        String encodedIcon=value.optString("icon","");
        QuickAction action=new QuickAction(java.util.UUID.randomUUID().toString(),value.getString("name"),
                value.getString("template"),parameters,value.optBoolean("privileged"),value.optString("mode","root"),
                payload == null ? null : payload.toString(),encodedIcon.isEmpty() ? null : android.util.Base64.decode(encodedIcon,0));
        if(validate) action.validate(); return action;
    }
}
