package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.databinding.ActivityMainBinding;
import com.fourtwo.hookintent.databinding.AppBarMainBinding;
import com.fourtwo.hookintent.utils.NetworkClient;
import com.fourtwo.hookintent.utils.RootServiceHelper;
import com.google.android.material.navigation.NavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存必要的状态
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // 恢复必要的状态
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

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化 NetworkClient
        NetworkClient networkClient = new NetworkClient();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        View headerView = binding.navView.getHeaderView(0);
        TextView versionTextView = headerView.findViewById(R.id.version);
        String now_version = getAppVersionName(getApplicationContext());


        networkClient.getVersion(Constants.GitHub_VERSION_URL, new NetworkClient.VersionCallback() {
            @Override
            public void onVersionReceived(String new_version) {
                // 处理收到的版本号
                Log.d(TAG, "最新版本号: " + new_version);
                versionTextView.setText(String.format("当前: v%s\n最新: v%s", now_version, new_version));
            }

            @Override
            public void onFailure(String errorMessage) {
                // 处理失败情况
                Log.e(TAG, errorMessage);
                versionTextView.setText(String.format("当前: v%s\n最新: %s", now_version, "未获取到版本号"));
            }
        });

        AppBarMainBinding appbarMain = AppBarMainBinding.bind(binding.getRoot().findViewById(R.id.app_bar_main));
        setSupportActionBar(appbarMain.toolbar);

        initializeUIComponents();

//        RootUtils.isRoot();
        // 绑定 RootService
        RootServiceHelper.bindRootService(this);

    }

    public void isRootStartActivity(Intent intent, Boolean isRoot) {
        try {
            if (isRoot) {
                RootServiceHelper.startActivityAsRoot(this, intent);
            } else {
                startActivity(intent);
            }
            Toast.makeText(this, "调用成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "无法启动新的 Intent: " + e.getMessage(), e);
            Toast.makeText(this, "调用失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeUIComponents() {
        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_me)
                .setOpenableLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RootServiceHelper.unbindRootService(this);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration) || super.onSupportNavigateUp();
    }

}
