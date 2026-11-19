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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class IntentLibraryFragment extends Fragment {
    private static final String ARG_PACKAGE = "libraryAppPackage";
    private static final String ARG_LABEL = "libraryAppLabel";
    private static final String STATE_QUERY = "library_query";
    private static final String STATE_SEARCH = "library_search_open";

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
            appRows = new AppRows(this::openApp);
            rows = appRows;
        } else {
            activityRows = new ActivityRows();
            rows = activityRows;
        }
        rows.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        recycler.setAdapter(rows);
        empty = view.findViewById(R.id.library_empty);
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
        return new Row(title, summary.toString(), () -> showActivity(activity));
    }

    private void showActivity(PackageActivityCatalog.Activity activity) {
        Context context = AppDialogs.context(requireContext());
        RecyclerView details = new RecyclerView(context);
        details.setLayoutManager(new LinearLayoutManager(context));
        details.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ActivityRows rows = new ActivityRows();
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

    private static final class AppRows extends RecyclerView.Adapter<AppRows.Holder> {
        private final Consumer<PackageActivityCatalog.App> open;
        private List<PackageActivityCatalog.App> rows = Collections.emptyList();

        AppRows(Consumer<PackageActivityCatalog.App> open) {
            this.open = open;
        }

        void submit(List<PackageActivityCatalog.App> result) {
            rows = result;
            notifyDataSetChanged();
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
            holder.itemView.setOnClickListener(view -> open.accept(app));
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title, packageName, version;

            Holder(View view) {
                super(view);
                icon = view.findViewById(R.id.library_app_icon);
                title = view.findViewById(R.id.library_app_title);
                packageName = view.findViewById(R.id.library_app_package);
                version = view.findViewById(R.id.library_app_version);
            }
        }
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

    private static final class ActivityRows extends RecyclerView.Adapter<ActivityRows.Holder> {
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
            holder.title.setVisibility(row.title.isEmpty() ? View.GONE : View.VISIBLE);
            holder.summary.setText(row.summary);
            holder.summary.setVisibility(row.summary.isEmpty() ? View.GONE : View.VISIBLE);
            holder.summary.setMaxLines(row.click == null ? Integer.MAX_VALUE : 3);
            holder.summary.setEllipsize(row.click == null ? null : TextUtils.TruncateAt.END);
            holder.summary.setTextIsSelectable(row.click == null && !row.summary.isEmpty());
            holder.itemView.setOnClickListener(row.click == null ? null : view -> row.click.run());
            holder.itemView.setClickable(row.click != null);
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
