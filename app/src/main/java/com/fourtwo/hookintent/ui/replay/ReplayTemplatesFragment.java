package com.fourtwo.hookintent.ui.replay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.replay.FragmentReplayStore;
import com.fourtwo.hookintent.replay.FragmentReplayStore.Template;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class ReplayTemplatesFragment extends Fragment {
    private TemplateAdapter adapter;

    public ReplayTemplatesFragment() {
        super(R.layout.fragment_replay_templates);
    }

    @Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        RecyclerView list = view.findViewById(R.id.replay_templates_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TemplateAdapter();
        list.setAdapter(adapter);
        view.findViewById(R.id.replay_template_import).setOnClickListener(v -> showImport());
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    @Override public void onDestroyView() {
        RecyclerView list = requireView().findViewById(R.id.replay_templates_list);
        list.setAdapter(null);
        adapter = null;
        super.onDestroyView();
    }

    private void refresh() {
        Context app = requireContext().getApplicationContext();
        run(() -> FragmentReplayStore.listTemplates(app), entries -> {
            adapter.replace(entries);
            requireView().findViewById(R.id.replay_templates_empty)
                    .setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void showImport() {
        Context context = requireContext();
        LinearLayout content = AppDialogs.content(context);
        AppCompatEditText template = AppDialogs.input(context);
        template.setHint(R.string.replay_templates_import_text);
        template.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        template.setGravity(Gravity.TOP | Gravity.START);
        template.setMinLines(3);
        template.setMaxLines(6);
        content.addView(template);
        AppCompatEditText id = ReplayTemplateDialogs.identifierInput(context, R.string.replay_templates_id_import);
        content.addView(id);
        ReplayTemplateDialogs.addIdentifierHelp(content);
        AlertDialog dialog = AppDialogs.builder(context)
                .setTitle(R.string.replay_templates_import)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.replay_templates_import, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = ReplayTemplateDialogs.inputValue(template);
            if (text.isEmpty()) {
                template.setError(getString(R.string.replay_templates_import_text));
                return;
            }
            String customId = ReplayTemplateDialogs.inputValue(id);
            Context app = context.getApplicationContext();
            ReplayTemplateDialogs.submit(dialog, () -> FragmentReplayStore.importTemplate(app, text, customId),
                    (result, error) -> {
                        if (error != null) {
                            showError(error);
                            return;
                        }
                        dialog.dismiss();
                        refresh();
                        if (!result.ready) showPending();
                    });
        });
    }

    private void showActions(Template entry) {
        Context app = requireContext().getApplicationContext();
        String[] options = {
                getString(R.string.navigation_replay_generated_intent), getString(R.string.replay_templates_export),
                getString(R.string.replay_templates_duplicate), getString(R.string.replay_templates_rename),
                getString(R.string.delete)
        };
        Runnable[] actions = {
                () -> {
                    if (entry.pending) {
                        showPending();
                    } else {
                        run(() -> FragmentReplayStore.templateIntent(app, entry.id),
                                intent -> ReplayTemplateDialogs.showIntent(requireActivity(), intent));
                    }
                },
                () -> run(() -> FragmentReplayStore.exportTemplate(app, entry.id), this::showExport),
                () -> showDuplicate(entry),
                () -> showRename(entry),
                () -> confirmDelete(entry)
        };
        AppDialogs.builder(requireContext())
                .setTitle(entry.name)
                .setItems(options, (dialog, which) -> actions[which].run())
                .show();
    }

    private void showPending() {
        AppDialogs.builder(requireContext())
                .setTitle(R.string.replay_templates_pending)
                .setMessage(R.string.replay_templates_pending_help)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showExport(String text) {
        Context context = requireContext();
        AppDialogs.builder(context)
                .setTitle(R.string.replay_templates_export)
                .setMessage(R.string.replay_templates_export_help)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.copy_am_code,
                        (dialog, which) -> ReplayTemplateDialogs.copy(context, R.string.replay_templates_title, text))
                .setPositiveButton(R.string.replay_templates_share, (dialog, which) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.replay_templates_share)));
                    } catch (RuntimeException error) {
                        showError(error);
                    }
                })
                .show();
    }

    private void showRename(Template entry) {
        Context context = requireContext();
        LinearLayout content = AppDialogs.content(context);
        AppCompatEditText name = AppDialogs.input(context);
        name.setHint(R.string.replay_templates_name_optional);
        name.setSingleLine(true);
        name.setText(entry.name);
        content.addView(name);
        AlertDialog dialog = AppDialogs.builder(context)
                .setTitle(R.string.replay_templates_rename)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = ReplayTemplateDialogs.inputValue(name);
            Context app = context.getApplicationContext();
            ReplayTemplateDialogs.submit(dialog, () -> {
                FragmentReplayStore.renameTemplate(app, entry.id, value);
                return null;
            }, (ignored, error) -> {
                if (error != null) {
                    showError(error);
                    return;
                }
                dialog.dismiss();
                refresh();
            });
        });
    }

    private void showDuplicate(Template entry) {
        Context context = requireContext();
        LinearLayout content = AppDialogs.content(context);
        AppCompatEditText id = ReplayTemplateDialogs.identifierInput(context, R.string.replay_templates_id_required);
        content.addView(id);
        ReplayTemplateDialogs.addIdentifierHelp(content);
        AlertDialog dialog = AppDialogs.builder(context)
                .setTitle(R.string.replay_templates_duplicate)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.copy_am_code, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = ReplayTemplateDialogs.inputValue(id);
            if (value.isEmpty()) {
                id.setError(getString(R.string.replay_templates_id_required));
                return;
            }
            Context app = context.getApplicationContext();
            ReplayTemplateDialogs.submit(dialog, () -> FragmentReplayStore.duplicateTemplate(app, entry.id, value),
                    (duplicate, error) -> {
                        if (error != null) {
                            showError(error);
                            return;
                        }
                        dialog.dismiss();
                        refresh();
                        if (duplicate.ready) {
                            run(() -> FragmentReplayStore.templateIntent(app, duplicate.id),
                                    intent -> ReplayTemplateDialogs.showIntent(requireActivity(), intent));
                        } else if (duplicate.pending) {
                            showPending();
                        }
                    });
        });
    }

    private void confirmDelete(Template entry) {
        Context app = requireContext().getApplicationContext();
        AppDialogs.builder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.replay_templates_delete_confirm, entry.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> run(() -> {
                    FragmentReplayStore.deleteTemplate(app, entry.id);
                    return null;
                }, ignored -> refresh()))
                .show();
    }

    private <T> void run(Callable<T> task, Consumer<T> complete) {
        View requestView = getView();
        ReplayTemplateDialogs.run(task, (value, error) -> {
            if (requestView == null || getView() != requestView) return;
            if (error == null) complete.accept(value);
            else showError(error);
        });
    }

    private void showError(Exception error) {
        if (getContext() != null) {
            ReplayTemplateDialogs.showError(requireContext(), R.string.replay_templates_error, error);
        }
    }

    private final class TemplateAdapter extends RecyclerView.Adapter<TemplateHolder> {
        private List<Template> entries = Collections.emptyList();

        void replace(List<Template> updated) {
            entries = updated;
            notifyDataSetChanged();
        }

        @NonNull @Override public TemplateHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new TemplateHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_replay_template, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull TemplateHolder holder, int position) {
            Template entry = entries.get(position);
            holder.name.setText(entry.name);
            String version;
            if (!TextUtils.isEmpty(entry.versionName)) {
                version = getString(R.string.replay_templates_version, entry.versionName, entry.versionCode);
            } else if (entry.versionCode >= 0) {
                version = getString(R.string.replay_templates_version_code, entry.versionCode);
            } else {
                version = getString(R.string.replay_templates_version_unknown);
            }
            holder.source.setText(getString(R.string.replay_templates_source, entry.packageName, version,
                    entry.destination));
            int status = entry.ready ? R.string.replay_templates_ready
                    : entry.pending ? R.string.replay_templates_pending : R.string.replay_templates_unavailable;
            holder.status.setText(getString(R.string.replay_templates_status, entry.id, getString(status)));
            holder.itemView.setOnClickListener(v -> showActions(entry));
        }

        @Override public int getItemCount() {
            return entries.size();
        }
    }

    private static final class TemplateHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView source;
        final TextView status;

        TemplateHolder(View view) {
            super(view);
            name = view.findViewById(R.id.replay_template_name);
            source = view.findViewById(R.id.replay_template_source);
            status = view.findViewById(R.id.replay_template_status);
        }
    }
}
