package com.fourtwo.hookintent.ui.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.AppInfoHelper;

import java.util.List;

public class ConnectedClientsAdapter extends RecyclerView.Adapter<ConnectedClientsAdapter.ViewHolder> {

    private final List<Bundle> connectedClients;

    public ConnectedClientsAdapter(List<Bundle> connectedClients) {
        this.connectedClients = connectedClients;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.dialog_custom_services_list, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Log.d("ConnectedClientsAdapter", "onBindViewHolder: Position " + position);

        // 从 connectedClients 获取当前项的 Bundle
        Bundle client = connectedClients.get(position);

        // 获取 Bundle 中的数据
        String packageName = client.getString("packageName", "Unknown");
        int uid = client.getInt("uid", -1);
        String className = client.getString("className", "Unknown");
        String processName = client.getString("processName", "Unknown");

        // 获取应用的名称和图标
        AppInfoHelper.AppInfo appInfo = AppInfoHelper.getAppInfo(holder.itemView.getContext(), packageName);
        String appName = appInfo.getAppName();
        Drawable appIcon = appInfo.getAppIcon();

        // 将数据绑定到 UI
        holder.icon.setImageDrawable(appIcon);
        holder.appName.setText(processName.replace(packageName, appName));
        holder.uid.setText("uid: " + uid);
        holder.packageName.setText("packageName: " + packageName);
        holder.className.setText("className: " + className);
        holder.processName.setText("processName: " + processName);
    }

    @Override
    public int getItemCount() {
        return connectedClients.size(); // 确保返回数据的大小
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName, uid, packageName, className, processName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            appName = itemView.findViewById(R.id.app_name);
            uid = itemView.findViewById(R.id.uid);
            packageName = itemView.findViewById(R.id.package_name);
            className = itemView.findViewById(R.id.class_name);
            processName = itemView.findViewById(R.id.process_name);
        }
    }
}
