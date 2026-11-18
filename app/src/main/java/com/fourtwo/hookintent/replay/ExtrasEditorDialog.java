package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.utils.AppDialogs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A private editing draft. Only Save hands a complete, validated snapshot back to its caller. */
public final class ExtrasEditorDialog {
    private ExtrasEditorDialog() { }

    public static void show(FragmentActivity activity, Bundle captureRecord, Consumer<Bundle> onSaved) {
        show(activity, captureRecord, R.string.extras_editor_title, R.string.extras_save, onSaved);
    }

    public static void show(FragmentActivity activity, Bundle captureRecord, int title,
            int confirmation, Consumer<Bundle> onSaved) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new Editor(activity, captureRecord, title, confirmation, onSaved).show();
    }

    private static final class Location {
        final String id, label;
        Location(String id, String label) { this.id = id; this.label = label; }
    }

    private static final class Editor {
        final FragmentActivity activity;
        final Context context;
        final Bundle draft;
        final Consumer<Bundle> onSaved;
        final int title, confirmation;
        final List<Location> trail = new ArrayList<>();
        final List<JSONObject> nodes = new ArrayList<>();
        final NodesAdapter adapter = new NodesAdapter();
        final LifecycleEventObserver lifecycle = (owner, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) close();
        };
        ExtrasClient client;
        AlertDialog dialog, valueDialog;
        TextView breadcrumb, status;
        Button back, retry;
        ProgressBar progress;
        RecyclerView list;
        boolean closed, busy, opened;
        int generation;

        Editor(FragmentActivity activity, Bundle record, int title, int confirmation, Consumer<Bundle> onSaved) {
            this.activity = activity;
            this.context = AppDialogs.context(activity);
            this.draft = new Bundle(record);
            this.title = title;
            this.confirmation = confirmation;
            this.onSaved = onSaved;
        }

        void show() {
            LinearLayout content = AppDialogs.content(context);
            // AlertDialog measures custom content with AT_MOST: a 0dp weighted list
            // otherwise collapses while loading/empty and never gets useful space.
            content.setMinimumHeight(Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.50f));
            content.setPadding(dp(20), dp(8), dp(20), 0);
            LinearLayout navigation = new LinearLayout(context);
            navigation.setGravity(Gravity.CENTER_VERTICAL);
            back = new Button(context);
            back.setText(R.string.extras_back);
            back.setMinWidth(0);
            back.setMinimumWidth(0);
            back.setMinHeight(dp(48));
            back.setTextSize(13);
            back.setTextColor(color(R.color.dialog_accent));
            navigation.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            breadcrumb = text(13, R.color.dialog_secondary);
            breadcrumb.setMaxLines(2);
            LinearLayout.LayoutParams pathParameters = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            pathParameters.setMarginStart(dp(8));
            navigation.addView(breadcrumb, pathParameters);
            content.addView(navigation);
            TextView explanation = text(12, R.color.dialog_secondary);
            explanation.setText(R.string.extras_editor_hint);
            explanation.setPadding(0, dp(4), 0, dp(8));
            content.addView(explanation);
            progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(true);
            content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            status = text(13, R.color.dialog_secondary);
            status.setPadding(0, dp(12), 0, dp(4));
            content.addView(status);
            retry = new Button(context);
            retry.setText(R.string.extras_retry);
            retry.setOnClickListener(view -> open());
            content.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list = new RecyclerView(context);
            list.setLayoutManager(new LinearLayoutManager(context));
            list.setAdapter(adapter);
            list.setClipToPadding(false);
            list.setPadding(0, 0, 0, dp(12));
            content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            dialog = AppDialogs.builder(context).setTitle(title).setView(content)
                    .setNegativeButton(R.string.extras_cancel, null)
                    .setPositiveButton(confirmation, null).create();
            dialog.setOnDismissListener(unused -> close());
            dialog.setOnKeyListener((unused, code, event) -> {
                if (code == KeyEvent.KEYCODE_BACK && trail.size() > 1) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !busy) up();
                    return true;
                }
                return false;
            });
            back.setOnClickListener(view -> up());
            dialog.show();
            AppDialogs.sizeForList(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save());
            activity.getLifecycle().addObserver(lifecycle);
            open();
        }

        boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

        void open() {
            if (!alive() || busy) return;
            generation++;
            int request = generation;
            if (valueDialog != null) { valueDialog.dismiss(); valueDialog = null; }
            if (client != null) client.close();
            client = new ExtrasClient(activity, new Bundle(draft));
            opened = false;
            nodes.clear();
            adapter.notifyDataSetChanged();
            trail.clear();
            trail.add(new Location("root", context.getString(R.string.extras_root)));
            updateTrail();
            setBusy(true);
            message(context.getString(R.string.extras_loading), false);
            client.open((result, error) -> {
                if (!alive() || request != generation) return;
                if (error != null) { setBusy(false); fail(error); return; }
                try {
                    updateDraft(result);
                    opened = true;
                    load();
                } catch (Exception failure) { setBusy(false); fail(failure); }
            });
        }

        void load() {
            if (!alive()) return;
            setBusy(true);
            int request = generation;
            client.children(trail.get(trail.size() - 1).id, (result, error) -> {
                if (!alive() || request != generation) return;
                setBusy(false);
                if (error != null) { fail(error); return; }
                try {
                    JSONArray children = new JSONArray(result.getString("nodes", "[]"));
                    nodes.clear();
                    for (int index = 0; index < children.length(); index++) nodes.add(children.getJSONObject(index));
                    adapter.notifyDataSetChanged();
                    updateTrail();
                    message(nodes.isEmpty() ? context.getString(R.string.extras_empty) : "", false);
                    list.scrollToPosition(0);
                } catch (Exception failure) { fail(failure); }
            });
        }

        void updateDraft(Bundle result) throws Exception {
            String parcel = result.getString("parcel");
            if (parcel == null || parcel.isEmpty()) throw new IllegalStateException(context.getString(R.string.extras_missing_snapshot));
            Bundle changes = new Bundle();
            if (result.getString("sourcePackage") != null) changes.putString("packageName", result.getString("sourcePackage"));
            changes.putString(IntentPayloadCodec.KEY, parcel);
            if (result.containsKey("uri")) changes.putString("uri", result.getString("uri"));
            String display = result.getString("intentExtras");
            if (display != null) {
                Bundle converted = JsonHandler.toBundle(new JSONObject().put("intentExtras", new JSONArray(display)));
                changes.putAll(converted);
            }
            draft.putAll(changes);
        }

        void enter(JSONObject node) {
            if (busy || !alive()) return;
            if (node.optBoolean("expandable")) {
                trail.add(new Location(node.optString("id"), node.optString("name")));
                updateTrail();
                load();
            } else if (node.optBoolean("editable")) edit(node);
            else if (node.optBoolean("nullable")) nullValue(node);
            else {
                String reason = node.optString("reason", node.optString("error", context.getString(R.string.extras_read_only)));
                AppDialogs.builder(context).setTitle(node.optString("name")).setMessage(reason)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        }

        void up() {
            if (!alive() || busy || trail.size() < 2) return;
            trail.remove(trail.size() - 1);
            updateTrail();
            load();
        }

        void updateTrail() {
            StringBuilder path = new StringBuilder();
            for (Location location : trail) {
                if (path.length() > 0) path.append(" / ");
                path.append(location.label);
            }
            breadcrumb.setText(path.toString());
            back.setVisibility(trail.size() > 1 ? View.VISIBLE : View.GONE);
            back.setEnabled(!busy);
        }

        void edit(JSONObject node) {
            if (!alive() || busy) return;
            LinearLayout form = AppDialogs.content(context);
            TextView type = text(12, R.color.dialog_secondary);
            type.setText(node.optString("type"));
            type.setPadding(0, 0, 0, dp(10));
            form.addView(type);
            Spinner typePicker = null;
            if (node.optBoolean("untyped")) {
                typePicker = new Spinner(context);
                typePicker.setContentDescription(context.getString(R.string.extras_value_type));
                String[] types = {"String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character"};
                ArrayAdapter<String> choices = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, types);
                choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                typePicker.setAdapter(choices);
                form.addView(typePicker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            AppCompatEditText input = AppDialogs.input(context);
            input.setTypeface(Typeface.MONOSPACE);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setMinLines(1);
            input.setMaxLines(8);
            String value = node.optString("value", "");
            if (node.optBoolean("isNull")) value = "";
            input.setText(value);
            input.setSelection(input.length());
            form.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            AlertDialog.Builder builder = AppDialogs.builder(context).setTitle(node.optString("name")).setView(form)
                    .setNegativeButton(R.string.extras_cancel, null).setPositiveButton(R.string.extras_apply, null);
            if (node.optBoolean("nullable")) builder.setNeutralButton(R.string.extras_set_null, null);
            AlertDialog child = builder.create();
            valueDialog = child;
            child.setOnDismissListener(unused -> { if (valueDialog == child) valueDialog = null; });
            child.show();
            Spinner finalTypePicker = typePicker;
            child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> apply(node, input.getText().toString(), false, child, input,
                    finalTypePicker == null ? null : "java.lang." + finalTypePicker.getSelectedItem().toString()));
            if (node.optBoolean("nullable")) child.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> apply(node, "", true, child, input));
        }

        void nullValue(JSONObject node) {
            if (!alive() || busy || !node.optBoolean("nullable")) return;
            AlertDialog child = AppDialogs.builder(context).setTitle(node.optString("name"))
                    .setMessage(R.string.extras_null_confirmation).setNegativeButton(R.string.extras_cancel, null)
                    .setPositiveButton(R.string.extras_set_null, null).create();
            valueDialog = child;
            child.setOnDismissListener(unused -> { if (valueDialog == child) valueDialog = null; });
            child.show();
            child.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> apply(node, "", true, child, null));
        }

        void apply(JSONObject node, String text, boolean makeNull, AlertDialog child, AppCompatEditText input) {
            apply(node, text, makeNull, child, input, null);
        }

        void apply(JSONObject node, String text, boolean makeNull, AlertDialog child, AppCompatEditText input, String selectedType) {
            if (!alive() || busy) return;
            setBusy(true);
            child.setCancelable(false);
            setChildButtons(child, false);
            int request = generation;
            ExtrasClient.Callback callback = (result, error) -> {
                if (!alive() || request != generation) return;
                setBusy(false);
                child.setCancelable(true);
                setChildButtons(child, true);
                if (error != null) {
                    if (input != null) input.setError(errorText(error));
                    fail(error);
                    return;
                }
                try {
                    updateDraft(result);
                    child.dismiss();
                    // A verified edit may rebuild the graph, so old node identifiers must not be reused.
                    trail.clear();
                    trail.add(new Location("root", context.getString(R.string.extras_root)));
                    updateTrail();
                    load();
                } catch (Exception failure) { fail(failure); }
            };
            if (selectedType == null) client.set(node.optString("id"), text, makeNull, callback);
            else client.setTyped(node.optString("id"), selectedType, text, makeNull, callback);
        }

        void setChildButtons(AlertDialog child, boolean enabled) {
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button button = child.getButton(which);
                if (button != null) button.setEnabled(enabled);
            }
        }

        void save() {
            if (!alive() || busy || !opened) return;
            try {
                onSaved.accept(new Bundle(draft));
                dialog.dismiss();
            } catch (RuntimeException error) { fail(error); }
        }

        void setBusy(boolean value) {
            busy = value;
            progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
            list.setEnabled(!value);
            back.setEnabled(!value);
            retry.setEnabled(!value);
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) save.setEnabled(opened && !value);
        }

        void message(String text, boolean error) {
            status.setText(text);
            status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            retry.setVisibility(error ? View.VISIBLE : View.GONE);
        }

        void fail(Exception error) { message(errorText(error), true); }

        String errorText(Exception error) {
            String message = error.getMessage();
            return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
        }

        void close() {
            if (closed) return;
            closed = true;
            generation++;
            activity.getLifecycle().removeObserver(lifecycle);
            if (client != null) client.close();
            if (valueDialog != null) { valueDialog.dismiss(); valueDialog = null; }
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        int dp(int value) { return AppDialogs.dp(context, value); }
        int color(int resource) { return ContextCompat.getColor(context, resource); }
        TextView text(int size, int color) {
            TextView result = new TextView(context);
            result.setTextSize(size);
            result.setTextColor(color(color));
            result.setLineSpacing(dp(2), 1);
            return result;
        }

        final class NodesAdapter extends RecyclerView.Adapter<NodeHolder> {
            @Override public NodeHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(12), 0, dp(12));
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView name = text(14, R.color.dialog_text);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                TextView kind = text(11, R.color.dialog_secondary);
                kind.setPadding(0, dp(3), 0, dp(5));
                TextView value = text(13, R.color.dialog_text);
                value.setTypeface(Typeface.MONOSPACE);
                value.setMaxLines(4);
                value.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name);
                row.addView(kind);
                row.addView(value);
                return new NodeHolder(row, name, kind, value);
            }

            @Override public void onBindViewHolder(NodeHolder holder, int position) {
                JSONObject node = nodes.get(position);
                boolean expandable = node.optBoolean("expandable");
                holder.name.setText(node.optString("name") + (expandable ? "  ›" : ""));
                holder.kind.setText(node.optString("type"));
                String value = node.optString("value", "");
                if (node.has("reference") && !node.isNull("reference")) {
                    value = context.getString(R.string.extras_reference, node.optString("reference")) + (value.isEmpty() ? "" : " · " + value);
                }
                holder.value.setText(value);
                holder.value.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
                holder.itemView.setOnClickListener(view -> enter(node));
                holder.itemView.setOnLongClickListener(view -> {
                    if (!busy && node.optBoolean("nullable")) { nullValue(node); return true; }
                    return false;
                });
                holder.itemView.setContentDescription(node.optString("name") + ", " + node.optString("type") + ", " + value);
            }

            @Override public int getItemCount() { return nodes.size(); }
        }
    }

    private static final class NodeHolder extends RecyclerView.ViewHolder {
        final TextView name, kind, value;
        NodeHolder(View row, TextView name, TextView kind, TextView value) {
            super(row); this.name = name; this.kind = kind; this.value = value;
        }
    }
}
