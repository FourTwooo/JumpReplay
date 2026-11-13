package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ReleaseInfo;
import com.fourtwo.hookintent.databinding.ActivityMainBinding;
import com.fourtwo.hookintent.databinding.AppBarMainBinding;
import com.fourtwo.hookintent.utils.NetworkClient;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.google.android.material.navigation.NavigationView;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private final NetworkClient networkClient = new NetworkClient();

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;
    private TextView versionTextView;

    public static boolean isXposed() {
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
    }

    private String readRawText(int resId) {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = getResources().openRawResource(resId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    builder.append("\n");
                }
                builder.append(line);
                first = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "readRawText failed", e);
        }
        return builder.toString();
    }
    public static String getAppVersionName(Context context) {
        String versionName = "";
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            versionName = pi.versionName;
            if (versionName == null || versionName.length() == 0) {
                return "";
            }
        } catch (Exception e) {
            Log.e(TAG, "VersionInfo Exception", e);
        }
        return versionName;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.fourtwo.hookintent.utils.RootServiceApi.warmUp(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        View headerView = binding.navView.getHeaderView(0);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(headerView, (header, insets) -> {
            int topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            header.setPadding(dpToPx(20), topInset + dpToPx(12), dpToPx(20), dpToPx(8));
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(headerView);
        versionTextView = headerView.findViewById(R.id.version);
        String currentVersion = getAppVersionName(this);
        versionTextView.setText(getString(R.string.version_current_latest, currentVersion, getString(R.string.version_not_available)));
        headerView.findViewById(R.id.github_link).setOnClickListener(view -> openWebPage(getString(R.string.nav_header_subtitle)));

        AppBarMainBinding appbarMain = AppBarMainBinding.bind(binding.getRoot().findViewById(R.id.app_bar_main));
        setSupportActionBar(appbarMain.toolbar);

        initializeUIComponents();
        initializeDefaultConfigs();
        openLatestCapture(getIntent());

        showLegalNoticeIfNeeded(() -> {
            refreshVersionHeader();
            checkForAppUpdate();
        });
    }

    private void initializeDefaultConfigs() {
        if (SharedPreferencesUtils.getStr(this, Constants.COLORS_CONFIG) == null) {
            SharedPreferencesUtils.putStr(this, Constants.COLORS_CONFIG, "{\"Intent\": \"#CE1A7EAC\", \"Scheme\": \"#47AA4B\"}");
        }

        if (SharedPreferencesUtils.getStr(this, Constants.FLOAT_WINDOW_CONFIG) == null) {
            SharedPreferencesUtils.putStr(this, Constants.FLOAT_WINDOW_CONFIG, "{\"float_window\": true, \"my_float_window\": false}");
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); openLatestCapture(intent);
    }
    private void openLatestCapture(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("show_latest_capture", false)) return;
        intent.removeExtra("show_latest_capture");
        com.fourtwo.hookintent.data.CaptureRepository.get(this).latestDetail((token, error) -> {
            if (isFinishing() || isDestroyed()) return;
            NavController nav = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
            if (token == null) {
                nav.navigate(R.id.nav_home);
                Toast.makeText(this, error == null ? "还没有收录记录" : error.getMessage(), Toast.LENGTH_LONG).show();
            } else {
                Bundle arguments = new Bundle();
                arguments.putString(com.fourtwo.hookintent.base.DetailPayloadStore.ARG_TOKEN, token);
                nav.navigate(R.id.nav_detail, arguments);
            }
        });
    }

    @SuppressLint("NonConstantResourceId")
    private void initializeUIComponents() {
        AtomicReference<DrawerLayout> drawer = new AtomicReference<>(binding.drawerLayout);
        NavigationView navigationView = binding.navView;

        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home,
                R.id.nav_star,
                R.id.nav_actions,
                R.id.nav_settings,
                R.id.nav_blocker,
                R.id.nav_history,
                R.id.nav_setup,
                R.id.nav_me
        ).setOpenableLayout(drawer.get()).build();

        AtomicReference<NavController> navController =
                new AtomicReference<>(Navigation.findNavController(this, R.id.nav_host_fragment_content_main));

        NavigationUI.setupActionBarWithNavController(this, navController.get(), mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController.get());

        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();

            if (itemId == R.id.nav_intercept) {
                startActivity(new Intent(this, IntentIntercept.class));
                drawer.get().closeDrawer(GravityCompat.START);
                return true;
            } else if (itemId == R.id.nav_disabled) {
                startActivity(new Intent(this, DisabledActivity.class));
                drawer.get().closeDrawer(GravityCompat.START);
                return true;
            } else if (itemId == R.id.nav_feedback) {
                openWebPage(Constants.GitHub_ISSUES_URL);
                drawer.get().closeDrawer(GravityCompat.START);
                return true;
            } else {
                navController.set(Navigation.findNavController(this, R.id.nav_host_fragment_content_main));
                boolean handled = NavigationUI.onNavDestinationSelected(item, navController.get());
                if (handled) {
                    drawer.get().closeDrawer(GravityCompat.START);
                }
                return handled;
            }
        });
    }

    private void refreshVersionHeader() {
        String currentVersion = getAppVersionName(getApplicationContext());

        networkClient.getVersion(Constants.GitHub_VERSION_URL, new NetworkClient.VersionCallback() {
            @Override
            public void onVersionReceived(String newVersion) {
                if (!canUpdateUi()) return;
                Log.d(TAG, "最新版本号: " + newVersion);
                versionTextView.setText(getString(R.string.version_current_latest, currentVersion, newVersion));
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!canUpdateUi()) return;
                Log.e(TAG, errorMessage);
                versionTextView.setText(
                        getString(
                                R.string.version_current_latest,
                                currentVersion,
                                getString(R.string.version_not_available)
                        )
                );
            }
        });
    }

    private void showLegalNoticeIfNeeded(@NonNull Runnable onAccepted) {
        String acceptedVersion = SharedPreferencesUtils.getStr(this, Constants.LEGAL_NOTICE_ACCEPTED_VERSION_KEY);
        if (Constants.LEGAL_NOTICE_VERSION.equals(acceptedVersion)) {
            onAccepted.run();
            return;
        }

        AlertDialog.Builder dialogBuilder = AppDialogs.builder(this);
        ScrollView scrollView = new ScrollView(dialogBuilder.getContext());
        int padding = dpToPx(20);
        scrollView.setFillViewport(true);

        TextView contentView = new TextView(dialogBuilder.getContext());
        contentView.setText(readRawText(R.raw.legal_notice));
        contentView.setTextSize(14f);
        contentView.setTextIsSelectable(false);
        contentView.setLongClickable(false);
        contentView.setClickable(false);
        contentView.setFocusable(false);
        contentView.setLineSpacing(0f, 1.25f);
        contentView.setPadding(padding, padding, padding, padding);

        scrollView.addView(contentView);

        AlertDialog dialog = dialogBuilder
                .setTitle(R.string.legal_notice_title)
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton(R.string.legal_notice_agree, (d, which) -> {
                    SharedPreferencesUtils.putStr(
                            this,
                            Constants.LEGAL_NOTICE_ACCEPTED_VERSION_KEY,
                            Constants.LEGAL_NOTICE_VERSION
                    );
                    onAccepted.run();
                })
                .setNegativeButton(R.string.legal_notice_disagree, (d, which) -> finishAffinity())
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void checkForAppUpdate() {
        networkClient.getReleasesCached(this, Constants.GitHub_RELEASES_URL, false, new NetworkClient.ReleaseListCallback() {
            @Override
            public void onSuccess(List<ReleaseInfo> releases) {
                if (!canUpdateUi()) return;
                ReleaseInfo latest = findLatestStableRelease(releases);
                if (latest == null) {
                    return;
                }

                String currentVersion = getAppVersionName(MainActivity.this);
                if (isRemoteVersionNewer(latest.getDisplayVersion(), currentVersion)) {
                    showUpdateDialog(latest);
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!canUpdateUi()) return;
                Log.e(TAG, "checkForAppUpdate: " + errorMessage);
            }
        });
    }

    private ReleaseInfo findLatestStableRelease(List<ReleaseInfo> releases) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }

        for (ReleaseInfo release : releases) {
            if (!release.isDraft() && !release.isPrerelease()) {
                return release;
            }
        }
        return null;
    }

    private boolean canUpdateUi() {
        return !isFinishing() && !isDestroyed();
    }

    private void showUpdateDialog(@NonNull ReleaseInfo release) {
        if (!canUpdateUi()) return;
        String currentVersion = getAppVersionName(this);

        AlertDialog.Builder dialogBuilder = AppDialogs.builder(this);
        ScrollView scrollView = new ScrollView(dialogBuilder.getContext());
        int padding = dpToPx(20);

        TextView contentView = new TextView(dialogBuilder.getContext());
        contentView.setText(
                getString(R.string.update_current_latest, currentVersion, normalizeVersionString(release.getDisplayVersion()))
                        + "\n\n"
                        + getString(R.string.history_published_at, formatPublishedAt(release.getPublishedAt()))
                        + "\n\n"
                        + (release.getBody().trim().isEmpty() ? getString(R.string.update_no_changelog) : release.getBody())
        );
        contentView.setTextSize(14f);
        contentView.setTextIsSelectable(true);
        contentView.setPadding(padding, padding, padding, padding);
        scrollView.addView(contentView);

        dialogBuilder
                .setTitle(R.string.update_found_title)
                .setView(scrollView)
                .setPositiveButton(R.string.update_action_now, (dialog, which) -> downloadAndInstallUpdate(release))
                .setNegativeButton(R.string.update_action_later, null)
                .show();
    }

    private void downloadAndInstallUpdate(@NonNull ReleaseInfo release) {
        if (release.getDownloadUrl().trim().isEmpty()) {
            Toast.makeText(this, R.string.update_download_url_missing, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, R.string.update_download_start, Toast.LENGTH_SHORT).show();

        File updateDir = new File(getCacheDir(), "update");
        if (!updateDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            updateDir.mkdirs();
        }

        String fileName = sanitizeFileName("JumpReplay_" + normalizeVersionString(release.getDisplayVersion()) + ".apk");
        File apkFile = new File(updateDir, fileName);

        networkClient.downloadFile(release.getDownloadUrl(), apkFile, new NetworkClient.DownloadFileCallback() {
            @Override
            public void onSuccess(File file) {
                if (!canUpdateUi()) return;
                Toast.makeText(MainActivity.this, R.string.update_download_done, Toast.LENGTH_SHORT).show();
                installDownloadedApk(file);
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!canUpdateUi()) return;
                Toast.makeText(
                        MainActivity.this,
                        getString(R.string.update_download_failed, errorMessage),
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    private void installDownloadedApk(@NonNull File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                apkFile
        );

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setData(apkUri);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, getPackageName());

        try {
            startActivity(installIntent);
            return;
        } catch (Throwable ignored) {
        }

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(viewIntent);
            return;
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())
                );
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settingsIntent);
                return;
            } catch (Throwable ignored) {
            }
        }

        Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show();
    }

    private boolean isRemoteVersionNewer(String remoteVersion, String localVersion) {
        String remote = normalizeVersionString(remoteVersion);
        String local = normalizeVersionString(localVersion);

        String[] remoteParts = remote.split("\\.");
        String[] localParts = local.split("\\.");

        int maxLength = Math.max(remoteParts.length, localParts.length);
        for (int i = 0; i < maxLength; i++) {
            int remotePart = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
            int localPart = i < localParts.length ? parseVersionPart(localParts[i]) : 0;

            if (remotePart > localPart) {
                return true;
            } else if (remotePart < localPart) {
                return false;
            }
        }
        return false;
    }

    private int parseVersionPart(String part) {
        try {
            String numeric = part.replaceAll("[^0-9]", "");
            if (numeric.isEmpty()) {
                return 0;
            }
            return Integer.parseInt(numeric);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String normalizeVersionString(String version) {
        if (version == null) {
            return "";
        }
        String result = version.trim();
        if (result.startsWith("v") || result.startsWith("V")) {
            result = result.substring(1);
        }
        int index = result.indexOf('-');
        if (index != -1) {
            result = result.substring(0, index);
        }
        return result;
    }

    private String formatPublishedAt(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        return raw.replace("T", " ").replace("Z", "");
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration) || super.onSupportNavigateUp();
    }
}
