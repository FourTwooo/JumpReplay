package com.fourtwo.hookintent.ui.setup;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fourtwo.hookintent.MainActivity;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.manager.PermissionManager;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.Map;

import rikka.shizuku.Shizuku;

public class SetupFragment extends Fragment {

    private static final String TAG = "SetupFragment";
    private static final int REQUEST_CODE_SHIZUKU_PERMISSION = 1; // 请求权限的 request code

    private Map<String, Boolean> FloatWindowConfig;

    // 将 View 的引用定义为成员变量
    private CardView shizukuCard;
    private TextView shizukuText;
    private ImageView shizukuIcon;
    private TextView shizukuDescription;

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup, container, false);
        FloatWindowConfig = JsonHandler.strToBoolean(JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(requireContext(), Constants.FLOAT_WINDOW_CONFIG)));
        Log.d(TAG, "onCreateView: " + JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(requireContext(), Constants.FLOAT_WINDOW_CONFIG)));

        // 获取布局中的 CardView 和相关控件
        shizukuCard = view.findViewById(R.id.card_shizuku_status);
        shizukuText = shizukuCard.findViewById(R.id.shizuku_text);
        shizukuIcon = shizukuCard.findViewById(R.id.shizuku_icon);
        shizukuDescription = view.findViewById(R.id.shizuku_description);

        CardView rootCard = view.findViewById(R.id.card_root_status);
        TextView rootText = rootCard.findViewById(R.id.root_text);
        ImageView rootIcon = rootCard.findViewById(R.id.root_icon);
        TextView rootDescription = view.findViewById(R.id.root_description);

        // 根据权限动态设置 Root 服务状态
        if (PermissionManager.isRootPermissionGranted) {
            rootText.setText("Root 服务可用");
            rootIcon.setImageResource(R.drawable.baseline_check_circle_outline_24); // 成功图标
            rootCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_green)); // 成功背景
            rootDescription.setText("");
        } else {
            rootText.setText("Root 服务不可用");
            rootIcon.setImageResource(R.drawable.outline_cancel_24); // 错误图标
            rootCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_red)); // 错误背景
            rootDescription.setText("部分功能不可用");
        }

        rootCard.setOnClickListener (v -> {});

        CardView xposedCard = view.findViewById(R.id.card_xposed_status);
        TextView xposedText = xposedCard.findViewById(R.id.xposed_text);
        ImageView xposedIcon = xposedCard.findViewById(R.id.xposed_icon);
        TextView xposedDescription = view.findViewById(R.id.xposed_description);
        // 根据权限动态设置 Root 服务状态
        if (MainActivity.isXposed()) {
            xposedText.setText("Xposed 框架可用");
            xposedIcon.setImageResource(R.drawable.baseline_check_circle_outline_24); // 成功图标
            xposedCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_green)); // 成功背景
            xposedDescription.setText("");
        } else {
            xposedText.setText("Xposed 框架不可用");
            xposedIcon.setImageResource(R.drawable.outline_cancel_24); // 错误图标
            xposedCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_red)); // 错误背景
            xposedDescription.setText("部分功能不可用(LSPatch则忽略)");
        }
        xposedCard.setOnClickListener (v -> {});

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 确保 UI 更新逻辑在视图创建后调用
        updateShizukuUI();

        // 设置 Shizuku Card 的长按监听器
        shizukuCard.setOnClickListener(v -> {
            if (!PermissionManager.isShizukuPermissionGranted) {
                if (PermissionManager.isBinderAvailable) {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION); // 请求 Shizuku 权限
                    Log.d(TAG, "请求 Shizuku 权限中...");
                } else {
                    Log.d(TAG, "Shizuku 服务不可用");
                }
            }
        });

        // 添加 Shizuku 权限结果监听器
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE_SHIZUKU_PERMISSION) {
                PermissionManager.isShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                updateShizukuUI();
                Log.d(TAG, "权限请求结果: " + (PermissionManager.isShizukuPermissionGranted ? "已授权" : "未授权"));
            }
        });


        // 获取主开关和子开关
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch mainSwitch = view.findViewById(R.id.float_window_main_switch);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch subSwitch = view.findViewById(R.id.float_window_sub_switch);

        mainSwitch.setChecked(Boolean.TRUE.equals(FloatWindowConfig.get("float_window")));
        if (mainSwitch.isChecked()){
            subSwitch.setEnabled(true);
        }
        subSwitch.setChecked(Boolean.TRUE.equals(FloatWindowConfig.get("my_float_window")));

        // 设置主开关监听器
        mainSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 根据主开关状态启用或禁用子开关
            subSwitch.setEnabled(isChecked);
            FloatWindowConfig.put("float_window", isChecked);
            SharedPreferencesUtils.putStr(requireContext(), Constants.FLOAT_WINDOW_CONFIG, JsonHandler.mapToJson(JsonHandler.booleanToStr(FloatWindowConfig)));
        });

        subSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FloatWindowConfig.put("my_float_window", isChecked);
            SharedPreferencesUtils.putStr(requireContext(), Constants.FLOAT_WINDOW_CONFIG, JsonHandler.mapToJson(JsonHandler.booleanToStr(FloatWindowConfig)));
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 移除监听器以避免内存泄漏
        Shizuku.removeRequestPermissionResultListener((requestCode, grantResult) -> {
        });
    }

    /**
     * 更新 Shizuku 的 UI 状态
     */
    @SuppressLint("SetTextI18n")
    private void updateShizukuUI() {
        if (PermissionManager.isBinderAvailable) { // 检查 Shizuku 服务是否可用
            if (PermissionManager.isShizukuPermissionGranted) {
                // Shizuku 已授权
                shizukuText.setText("Shizuku 服务可用");
                shizukuIcon.setImageResource(R.drawable.baseline_check_circle_outline_24); // 成功图标
                shizukuCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_green)); // 成功背景
                int shizukuUid = Shizuku.getUid();
                String ShizukuMode = shizukuUid == 2000 ? "adb" : ( shizukuUid == 0) ? "root" : "未知";
                shizukuDescription.setText("API " + Shizuku.getVersion() + ", " + ShizukuMode + "(" + shizukuUid + ")");
            } else {
                // Shizuku 未授权
                shizukuText.setText("Shizuku 服务未授权");
                shizukuIcon.setImageResource(R.drawable.outline_cancel_24); // 错误图标
                shizukuCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_red)); // 错误背景
                shizukuDescription.setText("请长按卡片以请求权限");
            }
        } else {
            // Shizuku 服务不可用
            shizukuText.setText("Shizuku 服务不可用");
            shizukuIcon.setImageResource(R.drawable.outline_cancel_24); // 错误图标
            shizukuCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.light_red)); // 错误背景
            shizukuDescription.setText("部分功能不可用");
        }
    }
}
