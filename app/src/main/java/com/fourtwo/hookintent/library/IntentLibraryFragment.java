package com.fourtwo.hookintent.library;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class IntentLibraryFragment extends Fragment {
    private static final String ARG_PACKAGE = "libraryAppPackage";
    private static final String ARG_LABEL = "libraryAppLabel";
    private static final String STATE_QUERY = "library_query";
    private static final String STATE_SEARCH = "library_search_open";
    private static final AccessibilityDelegateCompat ROW_SELECTION_ACCESSIBILITY = new AccessibilityDelegateCompat() {
        @Override public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                @NonNull AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            CompoundButton box = host.findViewById(R.id.library_row_selected);
            if (box.getVisibility() == View.VISIBLE) {
                info.setCheckable(true);
                info.setChecked(box.isChecked());
            }
        }
    };

    private IntentLibrary library;
    private RecyclerView recycler;
    private AppRows appRows;
    private ActivityRows activityRows;
    private TextView empty;
    private MenuItem searchItem;
    private SearchView search;
    private String packageName;
    private String query = "";
    private boolean searchOpen;
    private boolean loaded;
    private boolean loadFailed;
    private int generation;
    private List<PackageActivityCatalog.App> apps = Collections.emptyList();
    private List<PackageActivityCatalog.Activity> activities = Collections.emptyList();
    private final Set<String> selected = new LinkedHashSet<>();
    private ActionMode selectionMode;
    private boolean clearingSelection;
    private final OnBackPressedCallback selectionBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (selectionMode != null) selectionMode.finish();
        }
    };

    public IntentLibraryFragment() {
        super(R.layout.fragment_intent_library);
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Bundle arguments = getArguments();
        packageName = arguments == null ? null : arguments.getString(ARG_PACKAGE);
        if (state != null) {
            query = state.getString(STATE_QUERY, "");
            searchOpen = state.getBoolean(STATE_SEARCH);
        }
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        library = IntentLibrary.get(requireContext());
        recycler = view.findViewById(R.id.library_rows);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        RecyclerView.Adapter<?> rows;
        if (packageName == null) {
            appRows = new AppRows(this::openApp, this::toggleSelection);
            rows = appRows;
        } else {
            activityRows = new ActivityRows(this::toggleSelection);
            rows = activityRows;
        }
        rows.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        recycler.setAdapter(rows);
        empty = view.findViewById(R.id.library_empty);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), selectionBack);
        renderRows();
        installMenu();
        if (packageName != null) {
            library.changes().observe(getViewLifecycleOwner(), changed -> loadRows());
        }
        loadRows();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUERY, query);
        outState.putBoolean(STATE_SEARCH, searchOpen);
    }

    @Override public void onPause() {
        // The activity toolbar is shared with other destinations. Keep this destination's filter.
        if (search != null) search.setOnQueryTextListener(null);
        if (searchItem != null) searchItem.setOnActionExpandListener(null);
        super.onPause();
    }

    @Override public void onDestroyView() {
        if (selectionMode != null) selectionMode.finish();
        generation++;
        recycler.setAdapter(null);
        recycler = null;
        appRows = null;
        activityRows = null;
        empty = null;
        searchItem = null;
        search = null;
        super.onDestroyView();
    }

    private void installMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.intent_library, menu);
                menu.findItem(R.id.library_enabled).setChecked(library.enabled());
                searchItem = menu.findItem(R.id.library_search);
                search = (SearchView) searchItem.getActionView();
                configureSearch(searchItem, search);
            }

            @Override public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.library_enabled) {
                    boolean enabled = !library.enabled();
                    library.setEnabled(enabled);
                    item.setChecked(enabled);
                    return true;
                }
                if (item.getItemId() != R.id.library_clear) return false;
                AppDialogs.builder(requireContext()).setTitle(R.string.library_clear)
                        .setMessage(R.string.library_clear_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete, (dialog, which) -> changeCache(library::clear, null))
                        .show();
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void configureSearch(MenuItem item, SearchView field) {
        String previousQuery = query;
        field.setMaxWidth(Integer.MAX_VALUE);
        field.setQueryHint(getString(packageName == null
                ? R.string.library_search_apps : R.string.library_search_activities));
        field.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String text) {
                updateQuery(text);
                field.clearFocus();
                return true;
            }

            @Override public boolean onQueryTextChange(String text) {
                updateQuery(text);
                return true;
            }
        });
        item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(@NonNull MenuItem expanded) {
                searchOpen = true;
                return true;
            }

            @Override public boolean onMenuItemActionCollapse(@NonNull MenuItem collapsed) {
                searchOpen = false;
                updateQuery("");
                return true;
            }
        });
        View content = getView();
        if (searchOpen && content != null) {
            content.post(() -> {
                if (searchItem != item || !isResumed()) return;
                if (!item.isActionViewExpanded() && !item.expandActionView()) {
                    searchOpen = false;
                    updateQuery("");
                    return;
                }
                field.setQuery(previousQuery, false);
                field.clearFocus();
            });
        }
    }

    private void updateQuery(String text) {
        if (query.equals(text)) return;
        query = text;
        renderRows();
        if (recycler != null) recycler.scrollToPosition(0);
    }

    private boolean toggleSelection(String key) {
        if (key == null || !isResumed()) return false;
        if (clearingSelection) return true;
        if (selectionMode == null) {
            if (search != null) search.clearFocus();
            selectionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(selectionActions);
            if (selectionMode == null) return false;
            selectionBack.setEnabled(true);
        }
        if (!selected.add(key)) selected.remove(key);
        updateSelection();
        return true;
    }

    private final ActionMode.Callback selectionActions = new ActionMode.Callback() {
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.intent_library_selection, menu);
            return true;
        }

        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            menu.findItem(R.id.library_clear_selected).setEnabled(!clearingSelection && !selected.isEmpty());
            menu.findItem(R.id.library_select_all).setEnabled(!clearingSelection);
            return true;
        }

        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (clearingSelection) return true;
            if (item.getItemId() == R.id.library_clear_selected) {
                confirmClearSelection(mode);
                return true;
            }
            if (item.getItemId() == R.id.library_select_all) {
                if (appRows != null) appRows.selectAll(selected);
                if (activityRows != null) activityRows.selectAll(selected);
                updateSelection();
                return true;
            }
            return false;
        }

        @Override public void onDestroyActionMode(ActionMode mode) {
            if (selectionMode != mode) return;
            selectionMode = null;
            selected.clear();
            clearingSelection = false;
            selectionBack.setEnabled(false);
            updateSelection();
        }
    };

    private void updateSelection() {
        if (selectionMode != null && selected.isEmpty()) {
            selectionMode.finish();
            return;
        }
        Set<String> selection = selectionMode == null ? null : selected;
        if (appRows != null) appRows.setSelection(selection);
        if (activityRows != null) activityRows.setSelection(selection);
        if (selectionMode != null) {
            int count = selected.size();
            selectionMode.setTitle(getResources().getQuantityString(R.plurals.library_selected_count, count, count));
            selectionMode.invalidate();
        }
    }

    private void confirmClearSelection(ActionMode mode) {
        if (selected.isEmpty()) return;
        boolean clearingApps = packageName == null;
        List<String> packages = new ArrayList<>(selected);
        List<PackageActivityCatalog.Activity> entries = new ArrayList<>();
        if (!clearingApps) {
            for (PackageActivityCatalog.Activity activity : activities) {
                if (selected.contains(activity.selectionKey)) entries.add(activity);
            }
        }
        int count = clearingApps ? packages.size() : entries.size();
        if (count == 0) return;
        CharSequence message = getResources().getQuantityString(clearingApps
                ? R.plurals.library_clear_selected_apps_message
                : R.plurals.library_clear_selected_activities_message, count, count);
        AppDialogs.builder(requireContext()).setTitle(R.string.library_clear_selected)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    clearingSelection = true;
                    mode.invalidate();
                    changeCache(() -> {
                        if (clearingApps) library.deleteApps(packages);
                        else library.deleteActivities(packageName, entries);
                    }, () -> {
                        if (selectionMode == mode) mode.finish();
                        Toast.makeText(requireContext(), R.string.library_selected_cleared, Toast.LENGTH_SHORT).show();
                    }, () -> {
                        if (selectionMode == mode) {
                            clearingSelection = false;
                            mode.invalidate();
                        }
                    });
                }).show();
    }

    private void loadRows() {
        int request = ++generation;
        Context context = requireContext().getApplicationContext();
        IntentLibrary.UI_IO.execute(() -> {
            try {
                if (packageName == null) {
                    List<PackageActivityCatalog.App> result = PackageActivityCatalog.applications(context);
                    ContextCompat.getMainExecutor(context).execute(() -> {
                        if (recycler == null || generation != request) return;
                        apps = result;
                        finishLoading(false);
                    });
                } else {
                    List<PackageActivityCatalog.Entry> declared;
                    try {
                        declared = PackageActivityCatalog.activities(context, packageName);
                    } catch (PackageManager.NameNotFoundException removed) {
                        declared = Collections.emptyList();
                    }
                    List<PackageActivityCatalog.Activity> result = PackageActivityCatalog.merge(
                            declared, library.observationsForApp(packageName));
                    ContextCompat.getMainExecutor(context).execute(() -> {
                        if (recycler == null || generation != request) return;
                        activities = result;
                        finishLoading(false);
                    });
                }
            } catch (RuntimeException error) {
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (recycler == null || generation != request) return;
                    finishLoading(true);
                });
            }
        });
    }

    private void finishLoading(boolean failed) {
        loaded = true;
        loadFailed = failed;
        if (!failed && selectionMode != null) {
            Set<String> existing = new LinkedHashSet<>();
            if (packageName == null) {
                for (PackageActivityCatalog.App app : apps) existing.add(app.packageName);
            } else {
                for (PackageActivityCatalog.Activity activity : activities) existing.add(activity.selectionKey);
            }
            if (selected.retainAll(existing)) updateSelection();
        }
        renderRows();
    }

    private void renderRows() {
        if (recycler == null) return;
        String filter = query.trim().toLowerCase(Locale.ROOT);
        boolean hasRows;
        if (packageName == null) {
            List<PackageActivityCatalog.App> matches = new ArrayList<>();
            for (PackageActivityCatalog.App app : apps) {
                if (matches(filter, app.label) || matches(filter, app.packageName)
                        || matches(filter, app.versionName) || matches(filter, Long.toString(app.versionCode))) {
                    matches.add(app);
                }
            }
            appRows.submit(matches);
            hasRows = !matches.isEmpty();
        } else {
            List<Row> matches = new ArrayList<>();
            for (PackageActivityCatalog.Activity activity : activities) {
                if (matchesActivity(filter, activity)) matches.add(activityRow(activity));
            }
            activityRows.submit(matches);
            hasRows = !matches.isEmpty();
        }
        int message = loadFailed ? R.string.library_read_error
                : !filter.isEmpty() ? R.string.library_no_match
                : packageName == null ? R.string.library_no_apps : R.string.library_no_activities;
        empty.setText(message);
        empty.setVisibility(loaded && !hasRows ? View.VISIBLE : View.GONE);
    }

    private void openApp(PackageActivityCatalog.App app) {
        if (!isResumed()) return;
        if (selectionMode != null) {
            toggleSelection(app.packageName);
            return;
        }
        Bundle arguments = new Bundle();
        arguments.putString(ARG_PACKAGE, app.packageName);
        arguments.putString(ARG_LABEL, app.label);
        if (search != null) {
            search.setOnQueryTextListener(null);
            search.clearFocus();
        }
        if (searchItem != null) searchItem.setOnActionExpandListener(null);
        NavHostFragment.findNavController(this).navigate(R.id.nav_intent_library_activities, arguments);
    }

    private static boolean matches(String filter, String value) {
        return filter.isEmpty() || (value != null && value.toLowerCase(Locale.ROOT).contains(filter));
    }

    private boolean matchesActivity(String filter, PackageActivityCatalog.Activity activity) {
        if (matches(filter, activity.name) || matches(filter, activity.action) || matches(filter, activity.data)
                || matches(filter, activityTitle(activity))
                || (activity.declaration != null && matches(filter, activity.declaration.aliasTarget))) return true;
        for (IntentLibrary.Observation sample : activity.observations) {
            if (matches(filter, sample.action) || matches(filter, sample.data)) return true;
        }
        return false;
    }

    private String activityTitle(PackageActivityCatalog.Activity activity) {
        if (!activity.name.isEmpty()) return shortClass(activity.name);
        if (!activity.action.isEmpty()) return activity.action;
        if (!activity.data.isEmpty()) return activity.data;
        return getString(R.string.library_unresolved_activity);
    }

    private Row activityRow(PackageActivityCatalog.Activity activity) {
        String title = activityTitle(activity);
        StringBuilder summary = new StringBuilder();
        if (!activity.name.equals(title) && !activity.name.isEmpty()) summary.append(activity.name).append('\n');
        if (activity.declaration != null) {
            summary.append(getString(R.string.library_origin_declared))
                    .append(" · ").append(declarationSummary(activity.declaration));
        }
        if (!activity.observations.isEmpty()) {
            if (activity.declaration != null) summary.append(" · ");
            int count = activity.observations.size();
            summary.append(getResources().getQuantityString(R.plurals.library_sample_count, count, count));
        }
        return new Row(title, summary.toString(), () -> showActivity(activity), activity.selectionKey);
    }

    private void showActivity(PackageActivityCatalog.Activity activity) {
        Context context = AppDialogs.context(requireContext());
        RecyclerView details = new RecyclerView(context);
        details.setLayoutManager(new LinearLayoutManager(context));
        details.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ActivityRows rows = new ActivityRows(key -> false);
        details.setAdapter(rows);
        StringBuilder metadata = new StringBuilder();
        if (activity.declaration != null) {
            PackageActivityCatalog.Entry declaration = activity.declaration;
            metadata.append(declarationSummary(declaration));
            field(metadata, R.string.library_target_version, version(context, declaration.versionName, declaration.versionCode));
            field(metadata, R.string.library_permission, declaration.permission);
            field(metadata, R.string.library_alias, declaration.aliasTarget);
            if (declaration.launcher) {
                field(metadata, R.string.library_action, android.content.Intent.ACTION_MAIN);
                field(metadata, R.string.library_categories, android.content.Intent.CATEGORY_LAUNCHER);
            }
        } else {
            metadata.append(getString(activity.name.isEmpty() ? R.string.library_unresolved_activity
                    : R.string.library_origin_captured));
        }
        boolean hasDestination = activity.declaration != null || activity.observations.stream()
                .anyMatch(sample -> !sample.destinationPackage.isEmpty());
        field(metadata, hasDestination ? R.string.library_destination : R.string.library_source, packageName);
        field(metadata, R.string.library_activity, activity.name);
        field(metadata, R.string.library_action, activity.action);
        field(metadata, R.string.library_data, activity.data);

        AlertDialog dialog = AppDialogs.builder(context).setTitle(activityTitle(activity))
                .setView(details).setPositiveButton(R.string.library_close, null).create();
        List<IntentLibrary.Observation> samples = new ArrayList<>(activity.observations);
        renderDetails(rows, metadata.toString(), samples, activity.declaration != null, dialog);
        dialog.show();
    }

    private void renderDetails(ActivityRows rows, String metadata, List<IntentLibrary.Observation> samples,
            boolean declared, AlertDialog dialog) {
        List<Row> details = new ArrayList<>();
        details.add(new Row("", metadata, null));
        if (!samples.isEmpty()) {
            int count = samples.size();
            details.add(new Row(getResources().getQuantityString(R.plurals.library_sample_count, count, count), "", null));
        }
        for (IntentLibrary.Observation entry : samples) {
            String title = entry.action.isEmpty() ? entry.data : entry.action;
            if (title.isEmpty()) title = getString(R.string.library_observed_title);
            String summary = getString(entry.delivered ? R.string.library_delivered : R.string.library_requested)
                    + " · " + version(requireContext(), entry.destinationVersionName, entry.destinationVersionCode)
                    + " · " + getResources().getQuantityString(R.plurals.library_occurrences, entry.count, entry.count);
            if (!entry.data.isEmpty() && !entry.data.equals(title)) summary += "\n" + entry.data;
            details.add(new Row(title, summary, () -> showObservation(entry, () -> {
                samples.remove(entry);
                if (!declared && samples.isEmpty()) dialog.dismiss();
                else if (dialog.isShowing()) renderDetails(rows, metadata, samples, declared, dialog);
            })));
        }
        rows.submit(details);
    }
    private void showObservation(IntentLibrary.Observation entry, Runnable onDeleted) {
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
                .setNeutralButton(R.string.delete, (d, which) -> confirmDelete(entry, onDeleted));
        if (entry.intentUri != null) dialog.setPositiveButton(R.string.library_copy_uri, (d, which) -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Intent", entry.intentUri));
        });
        dialog.show();
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


    private void confirmDelete(IntentLibrary.Observation entry, Runnable onDeleted) {
        AppDialogs.builder(requireContext()).setTitle(R.string.library_delete_entry)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> changeCache(() -> library.delete(entry), onDeleted))
                .show();
    }

    private void changeCache(Runnable action, Runnable onSuccess) {
        changeCache(action, onSuccess, null);
    }

    private void changeCache(Runnable action, Runnable onSuccess, Runnable onFailure) {
        Context context = requireContext();
        View requestView = getView();
        IntentLibrary.UI_IO.execute(() -> {
            try {
                action.run();
                if (onSuccess != null) ContextCompat.getMainExecutor(context).execute(() -> {
                    if (getView() == requestView) onSuccess.run();
                });
            } catch (RuntimeException error) {
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (getView() == requestView) {
                        Toast.makeText(context, R.string.library_write_error, Toast.LENGTH_LONG).show();
                        if (onFailure != null) onFailure.run();
                    }
                });
            }
        });
    }

    private String declarationSummary(PackageActivityCatalog.Entry entry) {
        return getString(entry.exported ? R.string.library_exported : R.string.library_private)
                + " · " + getString(entry.enabled ? R.string.library_activity_on : R.string.library_activity_off)
                + (entry.launcher ? " · " + getString(R.string.library_launcher) : "");
    }

    private static String version(Context context, String name, long code) {
        if (code < 0) return context.getString(R.string.library_unknown_version);
        return name == null || name.isEmpty() ? String.valueOf(code) : name + " (" + code + ")";
    }

    private static String shortClass(String name) { return name.substring(name.lastIndexOf('.') + 1); }

    private static void bindSelection(View row, CompoundButton box, @Nullable Set<String> selection, String key) {
        boolean choosing = selection != null && key != null;
        boolean checked = choosing && selection.contains(key);
        box.setVisibility(choosing ? View.VISIBLE : View.GONE);
        box.setChecked(checked);
        row.setActivated(checked);
        row.setSelected(checked);
    }

    private static final class AppRows extends RecyclerView.Adapter<AppRows.Holder> {
        private final Consumer<PackageActivityCatalog.App> open;
        private final Predicate<String> select;
        private List<PackageActivityCatalog.App> rows = Collections.emptyList();
        private Set<String> selection;

        AppRows(Consumer<PackageActivityCatalog.App> open, Predicate<String> select) {
            this.open = open;
            this.select = select;
        }

        void submit(List<PackageActivityCatalog.App> result) {
            rows = result;
            notifyDataSetChanged();
        }

        void setSelection(@Nullable Set<String> selection) {
            this.selection = selection;
            notifyDataSetChanged();
        }

        void selectAll(Set<String> selected) {
            for (PackageActivityCatalog.App app : rows) selected.add(app.packageName);
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_intent_library_app, parent, false));
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            PackageActivityCatalog.App app = rows.get(position);
            Context context = holder.itemView.getContext();
            PackageManager manager = context.getPackageManager();
            holder.icon.setImageDrawable(app.applicationInfo == null ? manager.getDefaultActivityIcon()
                    : app.applicationInfo.loadIcon(manager));
            holder.title.setText(app.label);
            holder.packageName.setText(app.packageName);
            holder.version.setText(app.versionCode < 0 ? context.getString(R.string.library_unknown_version)
                    : context.getString(R.string.library_app_version, version(context, app.versionName, app.versionCode)));
            bindSelection(holder.itemView, holder.selected, selection, app.packageName);
            holder.itemView.setOnClickListener(view -> open.accept(app));
            holder.itemView.setOnLongClickListener(view -> select.test(app.packageName));
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title, packageName, version;
            final CompoundButton selected;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.library_app_icon);
                title = view.findViewById(R.id.library_app_title);
                packageName = view.findViewById(R.id.library_app_package);
                version = view.findViewById(R.id.library_app_version);
                selected = view.findViewById(R.id.library_row_selected);
                ViewCompat.setAccessibilityDelegate(view, ROW_SELECTION_ACCESSIBILITY);
            }
        }
    }

    private static final class Row {
        final String title, summary;
        final Runnable click;
        final String selectionKey;
        Row(String title, String summary, Runnable click) {
            this(title, summary, click, null);
        }
        Row(String title, String summary, Runnable click, String selectionKey) {
            this.title = title;
            this.summary = summary;
            this.click = click;
            this.selectionKey = selectionKey;
        }
    }

    private static final class ActivityRows extends RecyclerView.Adapter<ActivityRows.Holder> {
        private final Predicate<String> select;
        private List<Row> rows = Collections.emptyList();
        private Set<String> selection;

        ActivityRows(Predicate<String> select) {
            this.select = select;
        }

        void submit(List<Row> next) {
            rows = next;
            notifyDataSetChanged();
        }

        void setSelection(@Nullable Set<String> selection) {
            this.selection = selection;
            notifyDataSetChanged();
        }

        void selectAll(Set<String> selected) {
            for (Row row : rows) if (row.selectionKey != null) selected.add(row.selectionKey);
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_intent_library, parent, false));
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.title.setText(row.title);
            holder.title.setVisibility(row.title.isEmpty() ? View.GONE : View.VISIBLE);
            holder.summary.setText(row.summary);
            holder.summary.setVisibility(row.summary.isEmpty() ? View.GONE : View.VISIBLE);
            holder.summary.setMaxLines(row.click == null ? Integer.MAX_VALUE : 3);
            holder.summary.setEllipsize(row.click == null ? null : TextUtils.TruncateAt.END);
            holder.summary.setTextIsSelectable(row.click == null && !row.summary.isEmpty());
            bindSelection(holder.itemView, holder.selected, selection, row.selectionKey);
            holder.itemView.setOnClickListener(row.click == null ? null : view -> {
                if (selection != null && row.selectionKey != null) select.test(row.selectionKey);
                else row.click.run();
            });
            holder.itemView.setClickable(row.click != null);
            holder.itemView.setOnLongClickListener(row.selectionKey == null ? null : view -> select.test(row.selectionKey));
            holder.itemView.setLongClickable(row.selectionKey != null);
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title, summary;
            final CompoundButton selected;
            Holder(View view) {
                super(view);
                title = view.findViewById(R.id.library_row_title);
                summary = view.findViewById(R.id.library_row_summary);
                selected = view.findViewById(R.id.library_row_selected);
                ViewCompat.setAccessibilityDelegate(view, ROW_SELECTION_ACCESSIBILITY);
            }
        }
    }
}
