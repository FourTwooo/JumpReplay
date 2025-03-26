package com.fourtwo.hookintent;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.data.IntentMatchItem;

import java.util.List;

public class IntentInterceptAdapter extends RecyclerView.Adapter<IntentInterceptAdapter.ViewHolder> {

    private final Context context;
    private final List<IntentMatchItem> items;
    private final OnIntentMatchClickListener listener; // 回调接口

    public interface OnIntentMatchClickListener {
        void onIntentMatchClick(Intent intent); // 定义点击事件的回调方法
    }

    public IntentInterceptAdapter(Context context, List<IntentMatchItem> items, OnIntentMatchClickListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener; // 保存传入的监听器
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_intent_match, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IntentMatchItem item = items.get(position);

        // 设置应用图标
        holder.appIcon.setImageDrawable(item.getAppIcon());

        // 设置应用名称
        holder.appName.setText(item.getAppName());

        // 设置包名和活动路径
        holder.appDetails.setText(item.getAppDetails());

        // 打开按钮点击事件，通过回调传递给外部
        holder.openButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onIntentMatchClick(item.getIntent());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        TextView appDetails;
        ImageButton openButton;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            appDetails = itemView.findViewById(R.id.app_details);
            openButton = itemView.findViewById(R.id.open_button);
        }
    }
}
