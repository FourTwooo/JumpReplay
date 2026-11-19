package com.fourtwo.hookintent.library;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.utils.AppInfoHelper;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class IntentLibraryFragment extends Fragment {
    private IntentLibrary library;
    private Rows adapter;
    private EditText search;
    private TabLayout tabs;
    private TextView empty;
    private TextView back;
    private boolean catalog;
    private PackageActivityCatalog.App selectedApp;
    private List<PackageActivityCatalog.App> installedApps;
    private int generation;
    private int limit = IntentLibrary.PAGE_SIZE;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_intent_library, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        library = IntentLibrary.get(requireContext());
        RecyclerView recycler = view.findViewById(R.id.library_rows);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Rows();
        recycler.setAdapter(adapter);
        empty = view.findViewById(R.id.library_empty);
        back = view.findViewById(R.id.library_apps_back);
        search = view.findViewById(R.id.library_search);

        SwitchMaterial toggle = view.findViewById(R.id.library_enabled);
        toggle.setChecked(library.enabled());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            library.setEnabled(checked);
            if (!catalog) reload();
        });
        back.setOnClickListener(button -> chooseApp(null));
        tabs = view.findViewById(R.id.library_tabs);
        TabLayout.Tab tab = tabs.getTabAt(catalog ? 1 : 0);
        if (tab != null) tab.select();
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                catalog = tab.getPosition() == 1;
                limit = IntentLibrary.PAGE_SIZE;
                if (search.length() > 0) search.setText("");
                else reload();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                limit = IntentLibrary.PAGE_SIZE;
                reload();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                menu.add(R.id.nav_intent_library, R.id.library_clear, 0, R.string.library_clear);
            }
            @Override public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() != R.id.library_clear) return false;
                AppDialogs.builder(requireContext()).setTitle(R.string.library_clear)
                        .setMessage(R.string.library_clear_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete, (dialog, which) -> changeCache(library::clear))
                        .show();
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        library.changes().observe(getViewLifecycleOwner(), changed -> { if (!catalog) reload(); });
        reload();
    }

    @Override public void onDestroyView() {
        generation++;
        adapter = null;
        search = null;
        tabs = null;
        empty = null;
        back = null;
        super.onDestroyView();
    }

    private void reload() {
        int request = ++generation;
        Context context = requireContext();
        String query = search.getText().toString().trim();
        boolean showCatalog = catalog;
        PackageActivityCatalog.App app = selectedApp;
        List<PackageActivityCatalog.App> previousApps = installedApps;
        int visible = limit;
        back.setVisibility(showCatalog && app != null ? View.VISIBLE : View.GONE);
        if (showCatalog && app != null) back.setText(getString(R.string.library_current_app, app.label));
        IntentLibrary.UI_IO.execute(() -> {
            try {
                List<Row> result = new ArrayList<>();
                List<PackageActivityCatalog.App> allApps = previousApps;
                int message;
                if (!showCatalog) {
                    List<IntentLibrary.Observation> entries = library.page(query, visible + 1, 0);
                    for (int index = 0; index < Math.min(visible, entries.size()); index++) {
                        IntentLibrary.Observation entry = entries.get(index);
                        result.add(observationRow(context, entry));
                    }
                    if (entries.size() > visible) result.add(new Row(context.getString(R.string.library_more), "",
                            () -> {
                                limit += IntentLibrary.PAGE_SIZE;
                                reload();
                            }));
                    message = query.isEmpty() ? (library.enabled() ? R.string.library_empty : R.string.library_cache_off)
                            : R.string.library_no_match;
                } else if (app == null) {
                    if (allApps == null) allApps = PackageActivityCatalog.applications(context);
                    for (PackageActivityCatalog.App entry : allApps) {
                        if (matches(query, entry.label + " " + entry.packageName))
                            result.add(new Row(entry.label, entry.packageName, () -> chooseApp(entry)));
                    }
                    message = R.string.library_no_match;
                } else {
                    for (PackageActivityCatalog.Entry activity : PackageActivityCatalog.activities(context, app.packageName)) {
                        if (matches(query, activity.name)) {
                            result.add(new Row(shortClass(activity.name), declarationSummary(context, activity),
                                    () -> showDeclaration(activity)));
                        }
                    }
                    message = query.isEmpty() ? R.string.library_no_activities : R.string.library_no_match;
                }
                List<PackageActivityCatalog.App> loadedApps = allApps;
                requireMain(context, () -> {
                    if (adapter == null || generation != request) return;
                    installedApps = loadedApps;
                    adapter.submit(result);
                    empty.setText(message);
                    empty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                requireMain(context, () -> {
                    if (adapter == null || generation != request) return;
                    adapter.submit(Collections.emptyList());
                    empty.setText(R.string.library_read_error);
                    empty.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void chooseApp(PackageActivityCatalog.App app) {
        selectedApp = app;
        if (search.length() != 0) search.setText("");
        else reload();
    }

    private Row observationRow(Context context, IntentLibrary.Observation entry) {
        String title = entry.destinationPackage.isEmpty() ? entry.action
                : AppInfoHelper.getAppInfo(context, entry.destinationPackage).getAppName();
        if (!entry.activity.isEmpty()) title += " · " + shortClass(entry.activity);
        String summary = context.getString(entry.delivered ? R.string.library_delivered : R.string.library_requested)
                + " · " + version(context, entry.destinationVersionName, entry.destinationVersionCode)
                + " · " + context.getResources().getQuantityString(R.plurals.library_occurrences, entry.count, entry.count);
        return new Row(title, summary, () -> showObservation(entry));
    }

    private void showObservation(IntentLibrary.Observation entry) {
        Context context = requireContext();
        StringBuilder data = new StringBuilder(getString(entry.delivered ? R.string.library_delivered : R.string.library_requested));
        field(data, R.string.library_destination, entry.destinationPackage);
        field(data, R.string.library_activity, entry.activity);
        field(data, R.string.library_target_version, version(context, entry.destinationVersionName, entry.destinationVersionCode));
        field(data, R.string.library_source, entry.sourcePackage);
        field(data, R.string.library_source_version, version(context, entry.sourceVersionName, entry.sourceVersionCode));
        field(data, R.string.library_action, entry.action);
        field(data, R.string.library_data, entry.data);
        field(data, R.string.library_mime, entry.mimeType);
        field(data, R.string.library_categories, TextUtils.join("\n", entry.categories));
        field(data, R.string.library_flags, "0x" + Integer.toHexString(entry.flags));
        field(data, R.string.library_last_seen, DateFormat.getDateTimeInstance().format(new Date(entry.seenAt)));
        data.append("\n\n").append(getString(R.string.library_extras));
        if (entry.parameters.isEmpty()) data.append('\n').append(getString(entry.parametersComplete
                ? R.string.library_no_extras : R.string.library_extras_incomplete));
        for (IntentLibrary.Parameter extra : entry.parameters) {
            data.append('\n').append(extra.name).append(" [").append(extra.type).append("] = ")
                    .append(TextUtils.join(" · ", extra.examples));
        }
        if (!entry.parameters.isEmpty() && !entry.parametersComplete) data.append('\n').append(getString(R.string.library_extras_incomplete));
        androidx.appcompat.app.AlertDialog.Builder dialog = AppDialogs.builder(context)
                .setTitle(R.string.library_observed_title).setView(detailBody(data))
                .setNegativeButton(R.string.library_close, null)
                .setNeutralButton(R.string.delete, (d, which) -> confirmDelete(entry));
        if (entry.intentUri != null) dialog.setPositiveButton(R.string.library_copy_uri, (d, which) -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Intent", entry.intentUri));
        });
        dialog.show();
    }

    private void showDeclaration(PackageActivityCatalog.Entry entry) {
        StringBuilder data = new StringBuilder(declarationSummary(requireContext(), entry));
        field(data, R.string.library_destination, entry.packageName);
        field(data, R.string.library_activity, entry.name);
        field(data, R.string.library_target_version, version(requireContext(), entry.versionName, entry.versionCode));
        field(data, R.string.library_permission, entry.permission);
        field(data, R.string.library_alias, entry.aliasTarget);
        if (entry.launcher) {
            field(data, R.string.library_action, android.content.Intent.ACTION_MAIN);
            field(data, R.string.library_categories, android.content.Intent.CATEGORY_LAUNCHER);
        }
        data.append("\n\n").append(getString(R.string.library_declared_note));
        AppDialogs.builder(requireContext()).setTitle(R.string.library_declared_title).setView(detailBody(data))
                .setNegativeButton(R.string.library_close, null)
                .setPositiveButton(R.string.library_show_observations, (dialog, button) -> {
                    if (tabs == null || search == null) return;
                    TabLayout.Tab observed = tabs.getTabAt(0);
                    if (observed != null) observed.select();
                    search.setText(entry.aliasTarget == null ? entry.name : entry.aliasTarget);
                }).show();
    }

    private View detailBody(CharSequence value) {
        Context themed = AppDialogs.context(requireContext());
        ScrollView scroll = new ScrollView(themed);
        TextView details = new TextView(themed);
        details.setTextAppearance(R.style.TextAppearance_HookIntent_DialogBody);
        details.setTextIsSelectable(true);
        int padding = AppDialogs.dp(themed, 20);
        details.setPadding(padding, padding, padding, padding);
        details.setText(value);
        scroll.addView(details);
        return scroll;
    }

    private void field(StringBuilder body, int label, String value) {
        if (value == null || value.isEmpty()) return;
        body.append("\n\n").append(getString(label)).append('\n').append(value);
    }

    private void confirmDelete(IntentLibrary.Observation entry) {
        AppDialogs.builder(requireContext()).setTitle(R.string.library_delete_entry)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> changeCache(() -> library.delete(entry))).show();
    }

    private void changeCache(Runnable action) {
        Context context = requireContext();
        IntentLibrary.UI_IO.execute(() -> {
            try { action.run(); }
            catch (RuntimeException error) {
                requireMain(context, () -> {
                    if (isAdded()) Toast.makeText(context, R.string.library_write_error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void requireMain(Context context, Runnable callback) {
        androidx.core.content.ContextCompat.getMainExecutor(context).execute(callback);
    }

    private static String declarationSummary(Context context, PackageActivityCatalog.Entry entry) {
        return context.getString(entry.exported ? R.string.library_exported : R.string.library_private)
                + " · " + context.getString(entry.enabled ? R.string.library_activity_on : R.string.library_activity_off)
                + (entry.launcher ? " · " + context.getString(R.string.library_launcher) : "");
    }

    private static String version(Context context, String name, long code) {
        if (code < 0) return context.getString(R.string.library_unknown_version);
        return name == null || name.isEmpty() ? String.valueOf(code) : name + " (" + code + ")";
    }

    private static String shortClass(String name) { return name.substring(name.lastIndexOf('.') + 1); }

    private static boolean matches(String query, String value) {
        return query.isEmpty() || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static final class Row {
        final String title, summary;
        final Runnable click;
        Row(String title, String summary, Runnable click) {
            this.title = title;
            this.summary = summary;
            this.click = click;
        }
    }

    private static final class Rows extends RecyclerView.Adapter<Rows.Holder> {
        private List<Row> rows = Collections.emptyList();

        void submit(List<Row> next) {
            rows = next;
            notifyDataSetChanged();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_intent_library, parent, false));
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.title.setText(row.title);
            holder.summary.setText(row.summary);
            holder.summary.setVisibility(row.summary.isEmpty() ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(view -> row.click.run());
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title, summary;
            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.library_row_title);
                summary = view.findViewById(R.id.library_row_summary);
            }
        }
    }
}
