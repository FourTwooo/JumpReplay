package com.fourtwo.hookintent.ui.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.utils.AppInfoHelper;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SettingsAdapter extends RecyclerView.Adapter<SettingsAdapter.SettingsViewHolder> {

    private final List<Map<String, Object>> dataList;

    public interface DeleteCallback {
        void onDeleteItem(int position);
    }

    private DeleteCallback deleteCallback;

    public SettingsAdapter(List<Map<String, Object>> dataList, EditCallback editCallback, DeleteCallback deleteCallback) {
        this.dataList = dataList;
        this.editCallback = editCallback;
        this.deleteCallback = deleteCallback;
    }


    @NonNull
    @Override
    public SettingsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_settings_list, parent, false);
        return new SettingsViewHolder(view);
    }

    public interface EditCallback {
        void onEditItem(Map<String, Object> currentItem, int position);
    }


    private EditCallback editCallback;

    private AppInfoHelper.AppInfo getAppInfo(Context context, String packageName) {
        AppInfoHelper appInfoHelper = new AppInfoHelper(context);
        AppInfoHelper.AppInfo appInfo = appInfoHelper.getAppInfo(packageName);
        if (appInfo != null) {
            return appInfo;
        }
        return new AppInfoHelper.AppInfo(
                "未知应用" + ("/".equals(packageName) || "null".equals(packageName) ? "" : String.format("(%s)", packageName)),
                ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull SettingsViewHolder holder, int position) {
        Map<String, Object> item = dataList.get(position);

        Context context = holder.itemView.getContext();

        // 设置显示内容
        holder.packageName.setText((String) item.get("packageName"));
        holder.methodName.setText((String) item.get("methodName"));
        holder.className.setText((String) item.get("className"));
        holder.category.setText((String) item.get("category"));

        holder.icon.setImageDrawable(getAppInfo(context, (String) item.get("packageName")).getAppIcon());

        // 设置分类的颜色背景
        String category = (String) item.get("category");
        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.badge_background);
        if (drawable instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) drawable;
            String COLORS_CONFIG = SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG);
            Map<String, String> COLORS = JsonHandler.jsonToMap(COLORS_CONFIG);

            if (COLORS.containsKey(category)) {
                background.setColor(Color.parseColor(COLORS.get(category)));
            } else {
                background.setColor(Color.GRAY); // 默认灰色
            }
            holder.category.setBackground(background);
        }

        // 设置开关状态
        Boolean isOpen = (Boolean) item.get("open");
        Log.d("SettingsAdapter", "Binding position: " + position + "," + item.get("_uuid") + " isOpen: " + isOpen);

        // 移除之前的监听器
        holder.switchToggle.setOnCheckedChangeListener(null);

        holder.switchToggle.setChecked(isOpen != null && isOpen);

        holder.switchToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("SettingsAdapter", "Switch toggled at position: " + position + "," + item.get("_uuid") + ", isChecked: " + isChecked);
            // 更新数据模型中的状态
            item.put("open", isChecked);
        });


        // 操作按钮逻辑
        holder.operate.setOnClickListener(v -> {
            List<String> options = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();

            options.add("删除");
            actions.add(() -> {
                if (deleteCallback != null) {
                    deleteCallback.onDeleteItem(position);
                }
            });

            options.add("编辑");
            actions.add(() -> {
                if (editCallback != null) {
                    editCallback.onEditItem(dataList.get(position), position);
                }
            });

            PopupMenu popupMenu = new PopupMenu(v.getContext(), v);
            for (int i = 0; i < options.size(); i++) {
                popupMenu.getMenu().add(Menu.NONE, i, i, options.get(i));
            }

            popupMenu.setOnMenuItemClickListener(item_ -> {
                int which = item_.getItemId();
                actions.get(which).run();
                return true;
            });

            popupMenu.show();
        });

        // 内置过滤的逻辑
        Boolean isInternal = (Boolean) item.get("internal");
        if (isInternal != null && isInternal) {
            holder.operate.setVisibility(View.GONE); // 隐藏操作按钮
            holder.operate.setOnClickListener(null); // 移除点击事件
        } else {
            holder.operate.setVisibility(View.VISIBLE); // 显示操作按钮
        }
    }


    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public static class SettingsViewHolder extends RecyclerView.ViewHolder {
        ImageView icon, operate;
        TextView category, packageName, methodName, className;
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        Switch switchToggle;

        public SettingsViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            operate = itemView.findViewById(R.id.operate); // Add operate here
            category = itemView.findViewById(R.id.category);
            packageName = itemView.findViewById(R.id.package_name);
            methodName = itemView.findViewById(R.id.method_name);
            className = itemView.findViewById(R.id.class_name);
            switchToggle = itemView.findViewById(R.id.switch_toggle);
        }
    }
}
