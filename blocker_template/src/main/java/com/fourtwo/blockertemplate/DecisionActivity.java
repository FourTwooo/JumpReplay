package com.fourtwo.blockertemplate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DecisionActivity extends Activity {

    private Intent originalIntent;
    private String uriText = "";
    private boolean expanded = false;

    private TextView textUriSingle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decision);

        //noinspection deprecation
        originalIntent = getIntent().getParcelableExtra(BlockerEntryActivity.EXTRA_ORIGINAL_INTENT);

        if (originalIntent != null) {
            uriText = originalIntent.getDataString();
            if (uriText == null || uriText.trim().isEmpty()) {
                try {
                    uriText = originalIntent.toUri(Intent.URI_INTENT_SCHEME);
                } catch (Exception ignored) {
                }
            }
        }

        textUriSingle = findViewById(R.id.text_uri_single);
        textUriSingle.setText(uriText);
        applyUriExpandedState(false);

        findViewById(R.id.overlay_root).setOnClickListener(v -> finishSilently());
        findViewById(R.id.card_root).setOnClickListener(v -> {
            // 吃掉点击事件，避免点击卡片区域误关闭
        });
        findViewById(R.id.layout_uri_box).setOnClickListener(v -> toggleUri());

        Button buttonEdit = findViewById(R.id.button_edit);
        Button buttonReject = findViewById(R.id.button_reject);
        Button buttonAllow = findViewById(R.id.button_allow);

        buttonReject.setOnClickListener(v -> finishSilently());
        buttonEdit.setOnClickListener(v -> openManageForEdit());
        buttonAllow.setOnClickListener(v -> allowForward());
    }

    private void toggleUri() {
        applyUriExpandedState(!expanded);
    }

    private void applyUriExpandedState(boolean newExpanded) {
        expanded = newExpanded;
        textUriSingle.setText(uriText);

        if (expanded) {
            textUriSingle.setSingleLine(false);
            textUriSingle.setMaxLines(5);
            textUriSingle.setEllipsize(null);
            textUriSingle.setMovementMethod(new ScrollingMovementMethod());
            textUriSingle.setVerticalScrollBarEnabled(true);
            textUriSingle.setScrollbarFadingEnabled(false);
            textUriSingle.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        } else {
            textUriSingle.setMovementMethod(null);
            textUriSingle.scrollTo(0, 0);
            textUriSingle.setVerticalScrollBarEnabled(false);
            textUriSingle.setSingleLine(true);
            textUriSingle.setMaxLines(1);
            textUriSingle.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    private void openManageForEdit() {
        Intent intent = new Intent(this, ManageActivity.class);
        intent.putExtra(ManageActivity.EXTRA_PREFILL_URI, uriText);
        startActivity(intent);
        finishSilently();
    }

    /**
     * 清除入口组件限制，同时保留原始跳转的任务和 URI 授权标志。
     */
    private Intent stripUnwantedFields(Intent intent) {
        return ForwardingIntents.forQuery(intent);
    }

    /**
     * 第一套查询：直接用 blocker 收到的原始 Intent
     */
    private Intent buildQueryIntentFromOriginal() {
        Intent queryIntent;

        if (originalIntent != null) {
            queryIntent = new Intent(originalIntent);
        } else {
            queryIntent = new Intent(Intent.ACTION_VIEW);
        }

        queryIntent = stripUnwantedFields(queryIntent);

        if (queryIntent.getAction() == null || queryIntent.getAction().trim().isEmpty()) {
            queryIntent.setAction(Intent.ACTION_VIEW);
        }

        if (queryIntent.getData() == null && uriText != null && !uriText.trim().isEmpty()) {
            try {
                queryIntent.setData(Uri.parse(uriText));
            } catch (Exception ignored) {
            }
        }

        return queryIntent;
    }

    /**
     * 第二套查询：完全按 URI 重新构造一个标准 VIEW Intent
     * 用于某些 ROM（比如华为）原始 Intent 查不到候选时回退
     */
    private Intent buildFallbackQueryIntent() {
        Intent queryIntent = new Intent(Intent.ACTION_VIEW);

        try {
            if (uriText != null && !uriText.trim().isEmpty()) {
                queryIntent.setData(Uri.parse(uriText));
            }
        } catch (Exception ignored) {
        }

        return queryIntent;
    }

    private List<ResolveInfo> queryMatchingApps(Intent intent) {
        PackageManager packageManager = getPackageManager();
        String currentPackageName = getPackageName();

        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA);
        List<ResolveInfo> result = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        List<String> visited = ForwardingHistory.withVisit(
                ForwardingIntents.readHistory(originalIntent), currentPackageName);

        for (ResolveInfo resolveInfo : resolveInfoList) {
            if (resolveInfo.activityInfo == null) {
                continue;
            }

            if (currentPackageName.equals(resolveInfo.activityInfo.packageName)) {
                continue;
            }

            if (ForwardingHistory.shouldExclude(ForwardingIntents.isBlockerEntry(resolveInfo),
                    resolveInfo.activityInfo.packageName, visited)) {
                continue;
            }

            String key = resolveInfo.activityInfo.packageName + "/" + resolveInfo.activityInfo.name;
            if (dedupe.contains(key)) {
                continue;
            }

            dedupe.add(key);
            result.add(resolveInfo);
        }

        return result;
    }

    private Intent createLaunchIntent(Intent baseIntent, ResolveInfo resolveInfo) {
        return ForwardingIntents.forLaunch(baseIntent, originalIntent, resolveInfo, getPackageName());
    }

    private void allowForward() {
        Intent queryIntent = buildQueryIntentFromOriginal();
        List<ResolveInfo> candidates = queryMatchingApps(queryIntent);

        if (candidates.isEmpty()) {
            Intent fallbackIntent = buildFallbackQueryIntent();
            candidates = queryMatchingApps(fallbackIntent);
            queryIntent = fallbackIntent;
        }

        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.candidate_none, Toast.LENGTH_SHORT).show();
            finishSilently();
            return;
        }

        if (candidates.size() == 1) {
            try {
                startActivity(createLaunchIntent(queryIntent, candidates.get(0)));
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            finishSilently();
            return;
        }

        showCandidateChooserDialog(candidates, queryIntent);
    }

    private void showCandidateChooserDialog(List<ResolveInfo> candidates, Intent baseIntent) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_candidate_chooser, null);
        View outside = dialogView.findViewById(R.id.dialog_outside);
        View card = dialogView.findViewById(R.id.dialog_card);
        ListView listView = dialogView.findViewById(R.id.list_candidates);

        CandidateAdapter adapter = new CandidateAdapter(candidates);
        listView.setAdapter(adapter);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        outside.setOnClickListener(v -> dialog.dismiss());
        card.setOnClickListener(v -> {
            // 吃掉点击事件，避免点到卡片本体也关闭
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                startActivity(createLaunchIntent(baseIntent, candidates.get(position)));
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> finishSilently());

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.getDecorView().setPadding(0, 0, 0, 0);
        }
    }
    private void finishSilently() {
        finish();
        overridePendingTransition(0, R.anim.decision_exit);
    }

    private final class CandidateAdapter extends BaseAdapter {

        private final List<ResolveInfo> data;
        private final PackageManager packageManager;

        CandidateAdapter(List<ResolveInfo> data) {
            this.data = data;
            this.packageManager = getPackageManager();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public ResolveInfo getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(DecisionActivity.this)
                        .inflate(R.layout.item_candidate_app, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.icon_app);
                holder.title = convertView.findViewById(R.id.text_app_title);
                holder.subtitle = convertView.findViewById(R.id.text_app_subtitle);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ResolveInfo info = getItem(position);

            holder.icon.setImageDrawable(info.loadIcon(packageManager));

            CharSequence label = info.loadLabel(packageManager);
            holder.title.setText(label != null ? label : info.activityInfo.packageName);
            holder.subtitle.setText(info.activityInfo.packageName);

            return convertView;
        }

        final class ViewHolder {
            ImageView icon;
            TextView title;
            TextView subtitle;
        }
    }
}
