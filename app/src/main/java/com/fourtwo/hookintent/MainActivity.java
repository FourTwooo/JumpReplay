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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.databinding.ActivityMainBinding;
import com.fourtwo.hookintent.databinding.AppBarMainBinding;
import com.fourtwo.hookintent.utils.NetworkClient;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.google.android.material.navigation.NavigationView;

import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;

    public static boolean isXposed() {
        return false;
    }
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

        // 初始化类型颜色
        if (SharedPreferencesUtils.getStr(this, Constants.COLORS_CONFIG) == null) {
            SharedPreferencesUtils.putStr(this, Constants.COLORS_CONFIG, "{\"Intent\": \"#CE1A7EAC\", \"Scheme\": \"#47AA4B\"}");
        }

        // 初始化悬浮窗设置
        if (SharedPreferencesUtils.getStr(this, Constants.FLOAT_WINDOW_CONFIG) == null) {
            SharedPreferencesUtils.putStr(this, Constants.FLOAT_WINDOW_CONFIG, "{\"float_window\": true, \"my_float_window\": false}");
        }

        Log.d(TAG, "onCreate: " + SharedPreferencesUtils.getStr(this, "hooksConfig"));

//        Log.d("drawableToBase64", ImagesBase64.drawableToBase64(this, R.drawable.delete));
    }

    @SuppressLint("NonConstantResourceId")
    private void initializeUIComponents() {
        AtomicReference<DrawerLayout> drawer = new AtomicReference<>(binding.drawerLayout);
        NavigationView navigationView = binding.navView;
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_star, R.id.nav_me, R.id.nav_settings, R.id.nav_setup)
                .setOpenableLayout(drawer.get())
                .build();
        AtomicReference<NavController> navController = new AtomicReference<>(Navigation.findNavController(this, R.id.nav_host_fragment_content_main));
        NavigationUI.setupActionBarWithNavController(this, navController.get(), mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController.get());
        navigationView.setNavigationItemSelectedListener(item -> {
            // 手动处理 nav_intercept 和 nav_disabled
            if (item.getItemId() == R.id.nav_intercept) {
                startActivity(new Intent(this, IntentIntercept.class));
                return true; // 返回 true 表示事件已处理
            } else if (item.getItemId() == R.id.nav_disabled) {
                startActivity(new Intent(this, DisabledActivity.class));
                return true; // 返回 true 表示事件已处理
            } else {
                // 其他菜单项交由 NavigationUI 自动处理
                navController.set(Navigation.findNavController(this, R.id.nav_host_fragment_content_main));
                boolean handled = NavigationUI.onNavDestinationSelected(item, navController.get());
                if (handled) {
                    drawer.set(binding.drawerLayout);
                    drawer.get().closeDrawer(GravityCompat.START); // 关闭侧边栏
                }
                return handled; // 返回 NavigationUI 是否处理了事件
            }
        });



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
