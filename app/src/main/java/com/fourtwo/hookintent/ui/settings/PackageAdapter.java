package com.fourtwo.hookintent.ui.settings;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.AppInfoHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PackageAdapter extends RecyclerView.Adapter<PackageAdapter.ViewHolder> {

    private final List<Map<String, Object>> packages;
    private final OnPackageClickListener listener; // 定义一个接口来处理点击事件

    public PackageAdapter(List<Map<String, Object>> appInfoHash, OnPackageClickListener listener) {
        this.packages = appInfoHash;
        this.listener = listener; // 接收点击监听器
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.dialog_custom_packages_list, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint({"SetTextI18n", "DefaultLocale"})
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
//        Log.d("PackageAdapter", "onBindViewHolder: Position " + position);

        // 获取当前项的数据
        Map<String, Object> client = packages.get(position);

        String packageName = (String) client.get("packageName");
        String appName = (String) client.get("appName");
        String version = (String) client.get("version");

        // 获取应用的名称和图标
        AppInfoHelper.AppInfo appInfo = AppInfoHelper.getAppInfo(holder.itemView.getContext(), packageName);
        Drawable appIcon = appInfo.getAppIcon();

        // 将数据绑定到 UI
        holder.icon.setImageDrawable(appIcon);
        holder.appName.setText(appName);
        holder.packageName.setText(packageName);
        if (!Objects.equals(version, "")) {
            holder.version.setText("版本: " + version);
        } else {
            holder.version.setText(version);
        }


        // 设置点击事件监听器
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPackageClicked(packageName); // 回调点击事件
            }
        });
    }

    @Override
    public int getItemCount() {
        return packages.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName, packageName, version;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            version = itemView.findViewById(R.id.version);
        }
    }

    // 定义一个接口，用于处理点击事件
    public interface OnPackageClickListener {
        void onPackageClicked(String packageName);
    }
}
