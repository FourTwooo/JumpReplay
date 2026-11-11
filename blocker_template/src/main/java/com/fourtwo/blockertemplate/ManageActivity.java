package com.fourtwo.blockertemplate;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ManageActivity extends Activity {

    public static final String EXTRA_PREFILL_URI = "extra_prefill_uri";

    private Button buttonTabSchemes;
    private Button buttonTabRules;
    private Button buttonAddRule;

    private ScrollView scrollSchemes;
    private ScrollView scrollRules;
    private LinearLayout containerSchemes;
    private LinearLayout containerRules;

    private List<BlockRule> rules = new ArrayList<>();
    private List<String> schemes = new ArrayList<>();

    private boolean showingSchemes = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage);

        buttonTabSchemes = findViewById(R.id.button_tab_schemes);
        buttonTabRules = findViewById(R.id.button_tab_rules);
        buttonAddRule = findViewById(R.id.button_add_rule);

        scrollSchemes = findViewById(R.id.scroll_schemes);
        scrollRules = findViewById(R.id.scroll_rules);
        containerSchemes = findViewById(R.id.container_schemes);
        containerRules = findViewById(R.id.container_rules);

        buttonTabSchemes.setOnClickListener(v -> showSchemesTab());
        buttonTabRules.setOnClickListener(v -> showRulesTab());
        buttonAddRule.setOnClickListener(v -> showRuleDialog(null, ""));

        reloadData();

        String prefillUri = getIntent().getStringExtra(EXTRA_PREFILL_URI);
        if (!TextUtils.isEmpty(prefillUri)) {
            showRulesTab();
            containerRules.post(() -> showRuleDialog(null, prefillUri));
        } else {
            showSchemesTab();
        }
    }

    private void reloadData() {
        rules = RuleStore.load(this);
        schemes = SelfManifestUtils.getHandledSchemes(this);
        refreshSchemes();
        refreshRules();
    }

    private void showSchemesTab() {
        showingSchemes = true;
        scrollSchemes.setVisibility(View.VISIBLE);
        scrollRules.setVisibility(View.GONE);
        buttonAddRule.setVisibility(View.GONE);
    }

    private void showRulesTab() {
        showingSchemes = false;
        scrollSchemes.setVisibility(View.GONE);
        scrollRules.setVisibility(View.VISIBLE);
        buttonAddRule.setVisibility(View.VISIBLE);
    }

    private void refreshSchemes() {
        containerSchemes.removeAllViews();

        if (schemes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.manage_no_schemes);
            containerSchemes.addView(empty);
            return;
        }

        for (String scheme : schemes) {
            TextView tv = new TextView(this);
            tv.setText(scheme);
            tv.setTextSize(16f);
            tv.setPadding(0, 0, 0, 24);
            containerSchemes.addView(tv);
        }
    }

    private void refreshRules() {
        containerRules.removeAllViews();

        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.manage_no_rules);
            containerRules.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < rules.size(); i++) {
            BlockRule rule = rules.get(i);
            View itemView = inflater.inflate(R.layout.item_rule, containerRules, false);

            TextView textPattern = itemView.findViewById(R.id.text_rule_pattern);
            TextView textMeta = itemView.findViewById(R.id.text_rule_meta);
            Button buttonEdit = itemView.findViewById(R.id.button_rule_edit);
            Button buttonToggle = itemView.findViewById(R.id.button_rule_toggle);
            Button buttonDelete = itemView.findViewById(R.id.button_rule_delete);

            textPattern.setText(rule.getPattern());
            textMeta.setText((rule.isRegex() ? "正则匹配" : "前缀匹配") + " | " + (rule.isEnabled() ? "已启用" : "已停用"));
            buttonToggle.setText(rule.isEnabled() ? "停用" : "启用");

            int index = i;

            buttonEdit.setOnClickListener(v -> showRuleDialog(index, rule.getPattern()));
            buttonToggle.setOnClickListener(v -> {
                rule.setEnabled(!rule.isEnabled());
                RuleStore.save(this, rules);
                refreshRules();
                Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show();
            });
            buttonDelete.setOnClickListener(v -> {
                rules.remove(index);
                RuleStore.save(this, rules);
                refreshRules();
                Toast.makeText(this, R.string.rule_deleted, Toast.LENGTH_SHORT).show();
            });

            itemView.setOnClickListener(v -> showRuleDialog(index, rule.getPattern()));
            containerRules.addView(itemView);
        }
    }

    private void showRuleDialog(Integer editIndex, String prefillText) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_rule, null);

        EditText inputPattern = dialogView.findViewById(R.id.input_rule_pattern);
        ImageButton buttonRegex = dialogView.findViewById(R.id.button_regex_toggle);
        CheckBox checkEnabled = dialogView.findViewById(R.id.check_rule_enabled);

        final boolean[] regex = {false};

        if (editIndex != null && editIndex >= 0 && editIndex < rules.size()) {
            BlockRule current = rules.get(editIndex);
            inputPattern.setText(current.getPattern());
            regex[0] = current.isRegex();
            checkEnabled.setChecked(current.isEnabled());
        } else {
            inputPattern.setText(prefillText);
            checkEnabled.setChecked(true);
        }

        updateRegexButton(buttonRegex, regex[0]);
        buttonRegex.setOnClickListener(v -> {
            regex[0] = !regex[0];
            updateRegexButton(buttonRegex, regex[0]);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editIndex == null ? R.string.rule_dialog_title_add : R.string.rule_dialog_title_edit)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String pattern = safe(inputPattern.getText().toString());
                    if (pattern.isEmpty()) {
                        inputPattern.setError(getString(R.string.rule_invalid));
                        return;
                    }

                    if (regex[0]) {
                        try {
                            Pattern.compile(pattern);
                        } catch (PatternSyntaxException e) {
                            inputPattern.setError(getString(R.string.rule_regex_invalid, e.getDescription()));
                            return;
                        }
                    }

                    if (editIndex != null && editIndex >= 0 && editIndex < rules.size()) {
                        BlockRule current = rules.get(editIndex);
                        current.setPattern(pattern);
                        current.setRegex(regex[0]);
                        current.setEnabled(checkEnabled.isChecked());
                    } else {
                        rules.add(new BlockRule(pattern, regex[0], checkEnabled.isChecked()));
                    }

                    RuleStore.save(this, rules);
                    refreshRules();
                    Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void updateRegexButton(ImageButton button, boolean enabled) {
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
