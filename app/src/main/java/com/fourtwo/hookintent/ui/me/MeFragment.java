package com.fourtwo.hookintent.ui.me;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.fourtwo.hookintent.MainActivity;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.databinding.FragmentMeBinding;
import com.fourtwo.hookintent.databinding.RowAboutActionBinding;
import com.fourtwo.hookintent.utils.AppDialogs;

import java.util.Scanner;

public final class MeFragment extends Fragment {
    private static final String QQ_JOIN_URL = "https://qm.qq.com/cgi-bin/qm/qr"
            + "?k=6JWWosRVV0rtISqQKNVU5QY8KT0sBQP8&jump_from=webapi"
            + "&authKey=kvD0trmJvJiWSeFVv1+WTUYBpalYGKh+dF3zgfpLDuByEmZF2wT8XXwC8QuT/tzQ";

    public MeFragment() {
        super(R.layout.fragment_me);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentMeBinding binding = FragmentMeBinding.bind(view);
        String version = MainActivity.getAppVersionName(requireContext());
        if (version.isEmpty()) {
            binding.aboutVersion.setVisibility(View.GONE);
        } else {
            binding.aboutVersion.setText(getString(R.string.about_version_name, version));
        }

        String repository = getString(R.string.nav_header_subtitle);
        addAction(binding.aboutProjectActions, R.id.about_repository, R.drawable.header_link_icon,
                R.string.about_repository, 0, true, () -> openLink(repository));
        addAction(binding.aboutProjectActions, R.id.about_releases, R.drawable.history,
                R.string.about_releases, 0, true, () -> openLink(repository + "/releases"));
        addAction(binding.aboutProjectActions, R.id.about_demos, R.drawable.action_play,
                R.string.about_demos, 0, true,
                () -> openLink(Uri.parse(repository).buildUpon().fragment("演示视频").build().toString()));
        addAction(binding.aboutProjectActions, R.id.about_feedback, R.drawable.help,
                R.string.about_feedback, 0, true, () -> openLink(Constants.GitHub_ISSUES_URL));

        addAction(binding.aboutSupportActions, R.id.about_community, R.drawable.slideshow,
                R.string.about_community, R.string.about_group_number, false, this::showCommunity);
        addAction(binding.aboutSupportActions, R.id.about_license, R.drawable.header_link_icon,
                R.string.about_license, R.string.about_license_value, true,
                () -> openLink(repository + "/blob/master/LICENSE"));
        addAction(binding.aboutSupportActions, R.id.about_agreement, R.drawable.help,
                R.string.legal_notice_title, 0, false, this::showAgreement);
    }

    private void addAction(LinearLayout section, @IdRes int id, @DrawableRes int icon,
            @StringRes int title, @StringRes int detail, boolean external, Runnable onClick) {
        RowAboutActionBinding row = RowAboutActionBinding.inflate(LayoutInflater.from(section.getContext()),
                section, false);
        row.getRoot().setId(id);
        row.icon.setImageResource(icon);
        row.title.setText(title);
        if (detail != 0) {
            row.detail.setText(detail);
            row.detail.setVisibility(View.VISIBLE);
        }
        row.indicator.setImageResource(external ? R.drawable.header_link_arrow : R.drawable.about_chevron);
        ViewCompat.setScreenReaderFocusable(row.getRoot(), true);
        row.getRoot().setOnClickListener(view -> onClick.run());
        section.addView(row.getRoot());
    }

    private void showCommunity() {
        String number = getString(R.string.about_group_number);
        AppDialogs.builder(requireContext()).setTitle(R.string.about_community)
                .setMessage(number)
                .setNeutralButton(R.string.about_copy_group, (dialog, which) -> copyGroupNumber(number))
                .setPositiveButton(R.string.about_join_group, (dialog, which) -> openLink(QQ_JOIN_URL))
                .show();
    }

    private void copyGroupNumber(String number) {
        Context context = requireContext();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.about_community), number));
        Toast.makeText(context, R.string.detail_copied, Toast.LENGTH_SHORT).show();
    }

    private void showAgreement() {
        String agreement;
        try (Scanner input = new Scanner(getResources().openRawResource(R.raw.legal_notice), "UTF-8")) {
            input.useDelimiter("\\A");
            agreement = input.hasNext() ? input.next() : "";
        }
        Context context = AppDialogs.context(requireContext());
        TextView text = new TextView(context);
        text.setTextAppearance(R.style.TextAppearance_HookIntent_DialogBody);
        text.setTextIsSelectable(true);
        text.setText(agreement);
        int padding = AppDialogs.dp(context, 20);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        AppDialogs.builder(requireContext()).setTitle(R.string.legal_notice_title).setView(scroll)
                .setPositiveButton(R.string.ok, null).show();
    }

    private void openLink(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(requireContext(), R.string.about_open_link_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
