package com.fourtwo.hookintent.ui.home;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.ViewHolder> implements Filterable {

    private List<ItemData> mData;
    private List<ItemData> filteredData;
    private final int normalColor = Color.WHITE; // 正常状态的颜色
    private final int pressedColor = Color.LTGRAY; // 触摸时的颜色

    public HomeAdapter(List<ItemData> data) {
        this.mData = data;
        this.filteredData = new ArrayList<>(data);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_item_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                List<ItemData> filteredList = new ArrayList<>();
                if (constraint == null || constraint.length() == 0) {
                    filteredList.addAll(mData);
                } else {
                    String filterPattern = constraint.toString().toLowerCase().trim();
                    for (ItemData item : mData) {
                        // 判断item是否包含过滤的字符串
                        if (item.getAppName().toLowerCase().contains(filterPattern) ||
                                item.getItem_from().toLowerCase().contains(filterPattern) ||
                                item.getItem_data().toLowerCase().contains(filterPattern)) {
                            filteredList.add(item);
                        }
                    }
                }
                FilterResults results = new FilterResults();
                results.values = filteredList;
                return results;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredData.clear();
                filteredData.addAll((List<ItemData>) results.values);
                notifyDataSetChanged();
            }
        };
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ItemData item = filteredData.get(position);
//        ItemData item = mData.get(position);
        holder.icon.setImageDrawable(item.getIcon());
        holder.appName.setText(item.getAppName());
        holder.item_from.setText(item.getItem_from());
        holder.item_data.setText(item.getItem_data());
        holder.timestamp.setText(item.getTimestamp());
        holder.dataSize.setText(item.getDataSize());

        String category = item.getCategory();
        holder.category.setText(category);
        Context context = holder.itemView.getContext();

        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.badge_background);
        if (!(drawable instanceof GradientDrawable)) {return;}
        GradientDrawable background = (GradientDrawable) drawable;
        String COLORS_CONFIG = SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG);
        Map<String, String> COLORS = JsonHandler.jsonToMap(COLORS_CONFIG);
        if (COLORS.containsKey(category)) {
            background.setColor(Color.parseColor(COLORS.get(category)));
        } else {
            background.setColor(Color.GRAY);
        }
        holder.category.setBackground(background);

        holder.itemView.setOnClickListener(v -> {
            NavController navController = Navigation.findNavController((Activity) v.getContext(), R.id.nav_host_fragment_content_main);

            Bundle bundle = new Bundle();
            bundle.putParcelable("itemData", item); // 使用 putParcelable

            navController.navigate(R.id.action_nav_home_to_nav_detail, bundle);
        });

        holder.itemView.setOnLongClickListener(v -> {
            showPopupWindow(v, item);
            return true;
        });

        holder.itemView.setOnTouchListener(new View.OnTouchListener() {
            private ValueAnimator animator;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startColorAnimation(holder.itemView, normalColor, pressedColor);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        startColorAnimation(holder.itemView, pressedColor, normalColor);
                        break;
                }
                return false;
            }

            private void startColorAnimation(final View view, int startColor, int endColor) {
                if (animator != null && animator.isRunning()) {
                    animator.cancel();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    animator = ValueAnimator.ofArgb(startColor, endColor);
                }
                assert animator != null;
                animator.setDuration(300);
                animator.addUpdateListener(animation -> view.setBackgroundColor((int) animation.getAnimatedValue()));
                animator.start();
            }
        });
    }

    private void showPopupWindow(View view, ItemData item) {
        // 实现弹出窗口逻辑，例如使用PopupWindow或AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
        builder.setTitle(item.getCategory())
                .setMessage(item.getItem_from() + "\n\n" + item.getItem_data())
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }


    public void removeItem(int position) {
        if (position >= 0 && position < filteredData.size()) {
            // 获取要删除的项目
            ItemData item = filteredData.get(position);

            // 从 filteredData 中移除
            filteredData.remove(position);

            // 从 mData 中移除相同的项目
            mData.remove(item);

            notifyItemRemoved(position);
        }
    }

    @Override
    public int getItemCount() {
        return filteredData.size();
    }

    public List<ItemData> getFilteredData() {
        return filteredData;
    }
    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<ItemData> newData) {
        this.mData = newData;
        this.filteredData = new ArrayList<>(newData);  // 更新 filteredData
        notifyDataSetChanged();  // Notify the adapter that the data has changed
    }

    public List<ItemData> getData() {
        return mData;
    }

    // 新增清空数据的方法
    @SuppressLint("NotifyDataSetChanged")
    public void clearData() {
        mData.clear();
        filteredData.clear();
        notifyDataSetChanged();  // 通知适配器数据已更改
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName, item_from, item_data, timestamp, dataSize, category;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            appName = itemView.findViewById(R.id.app_name);
            category = itemView.findViewById(R.id.category);
            item_from = itemView.findViewById(R.id.item_from);
            item_data = itemView.findViewById(R.id.package_name);
            timestamp = itemView.findViewById(R.id.timestamp);
            dataSize = itemView.findViewById(R.id.data_size);
        }
    }
}