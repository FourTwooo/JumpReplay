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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.utils.AppInfoHelper;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.suke.widget.SwitchButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SettingsAdapter extends RecyclerView.Adapter<SettingsAdapter.SettingsViewHolder> {

    private final List<Map<String, Object>> dataList;
    private final EditCallback editCallback;
    private final DeleteCallback deleteCallback;

    public interface DeleteCallback {
        void onDeleteItem(int position);
    }

    public interface EditCallback {
        void onEditItem(Map<String, Object> currentItem, int position);
    }

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

    @Override
    public void onBindViewHolder(@NonNull SettingsViewHolder holder, int position) {
        Map<String, Object> item = dataList.get(position);
        Context context = holder.itemView.getContext();

        String packageName = valueOf(item.get("packageName"));
        String methodName = valueOf(item.get("methodName"));
        String className = valueOf(item.get("className"));
        String category = valueOf(item.get("category"));

        holder.category.setText(category);
        holder.icon.setImageDrawable(AppInfoHelper.getAppInfo(context, packageName).getAppIcon());

        if (!methodName.isEmpty()) {
            holder.methodName.setVisibility(View.VISIBLE);
            holder.methodName.setText(methodName);
        } else {
            holder.methodName.setVisibility(View.GONE);
            holder.methodName.setText("");
        }

        if (!className.isEmpty()) {
            holder.className.setVisibility(View.VISIBLE);
            holder.className.setText(className);
        } else {
            holder.className.setVisibility(View.GONE);
            holder.className.setText("");
        }

        if (!packageName.isEmpty()) {
            holder.packageName.setVisibility(View.VISIBLE);
            holder.packageName.setText(packageName);
        } else {
            holder.packageName.setVisibility(View.GONE);
            holder.packageName.setText("");
        }

        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.badge_background);
        if (drawable instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) drawable.mutate();
            String COLORS_CONFIG = SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG);
            Map<String, String> COLORS = JsonHandler.jsonToMap(COLORS_CONFIG);

            if (COLORS.containsKey(category)) {
                background.setColor(Color.parseColor(COLORS.get(category)));
            } else {
                NavigationCaptureSource source = NavigationCaptureSource.forCategory(category);
                background.setColor(source == null ? Color.GRAY : Color.parseColor(source.defaultColor()));
            }
            holder.category.setBackground(background);
        }

        Boolean isOpen = (Boolean) item.get("open");
        holder.switchToggle.setOnCheckedChangeListener(null);
        holder.switchToggle.setChecked(isOpen != null && isOpen);
        holder.switchToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.put("open", isChecked);
            NavigationCaptureSource source = NavigationCaptureSource.forConfigurationRow(item);
            if (source != null) source.setEnabled(context, isChecked);
        });

        holder.operate.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return;
            }

            List<String> options = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();

            options.add("删除");
            actions.add(() -> {
                if (deleteCallback != null) {
                    deleteCallback.onDeleteItem(adapterPosition);
                }
            });

            options.add("编辑");
            actions.add(() -> {
                if (editCallback != null) {
                    editCallback.onEditItem(dataList.get(adapterPosition), adapterPosition);
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

        Boolean isInternal = (Boolean) item.get("internal");
        if (isInternal != null && isInternal) {
            holder.operate.setVisibility(View.GONE);
            holder.operate.setOnClickListener(null);
        } else {
            holder.operate.setVisibility(View.VISIBLE);
        }
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public static class SettingsViewHolder extends RecyclerView.ViewHolder {
        ImageView icon, operate;
        TextView category, packageName, methodName, className;
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        SwitchButton switchToggle;

        public SettingsViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            operate = itemView.findViewById(R.id.operate);
            category = itemView.findViewById(R.id.category);
            packageName = itemView.findViewById(R.id.package_name);
            methodName = itemView.findViewById(R.id.method_name);
            className = itemView.findViewById(R.id.class_name);
            switchToggle = itemView.findViewById(R.id.switch_toggle);
        }
    }

}