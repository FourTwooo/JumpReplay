package com.fourtwo.hookintent.ui.home;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;
import java.util.Objects;

import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.base.DetailPayloadStore;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.ViewHolder> {

    private List<ItemData> mData;
    private List<ItemData> filteredData;
    public HomeAdapter(List<ItemData> data) {
        this.mData = data != null ? new ArrayList<>(data) : new ArrayList<>();
        this.filteredData = new ArrayList<>(this.mData);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_item_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ItemData item = filteredData.get(position);

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
        if (!(drawable instanceof GradientDrawable)) {
            return;
        }

        GradientDrawable background = (GradientDrawable) drawable.mutate();
        String COLORS_CONFIG = SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG);
        Map<String, String> COLORS = JsonHandler.jsonToMap(COLORS_CONFIG);

        if (category != null && COLORS.containsKey(category)) {
            try { background.setColor(Color.parseColor(COLORS.get(category))); }
            catch (IllegalArgumentException e) { background.setColor(Color.GRAY); }
        } else {
            background.setColor(Color.GRAY);
        }
        holder.category.setBackground(background);

        holder.itemView.setOnClickListener(v -> {
            try {
                NavController navController = Navigation.findNavController(
                        (Activity) v.getContext(), R.id.nav_host_fragment_content_main);
                Bundle bundle = new Bundle();
                bundle.putString(DetailPayloadStore.ARG_TOKEN, DetailPayloadStore.save(v.getContext(), item));
                navController.navigate(R.id.action_nav_home_to_nav_detail, bundle);
            } catch (Exception error) {
                Toast.makeText(v.getContext(), "无法打开详情：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
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
                int normalColor = ContextCompat.getColor(v.getContext(), R.color.screen_bg);
                int pressedColor = ContextCompat.getColor(v.getContext(), R.color.screen_pressed);
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
                if (animator == null) {
                    return;
                }
                animator.setDuration(300);
                animator.addUpdateListener(animation ->
                        view.setBackgroundColor((int) animation.getAnimatedValue()));
                animator.start();
            }
        });
    }

    private void showPopupWindow(View view, ItemData item) {
        AlertDialog.Builder builder = AppDialogs.builder(view.getContext());
        builder.setTitle(item.getCategory())
                .setMessage(String.valueOf(item.getItem_from()) + "\n\n" + String.valueOf(item.getItem_data()))
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    public void removeItem(int position) {
        if (position >= 0 && position < filteredData.size()) {
            ItemData item = filteredData.get(position);
            filteredData.remove(position);
            if (mData != filteredData) mData.remove(item);
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

    public void setData(List<ItemData> newData) {
        List<ItemData> next = newData == null ? new ArrayList<>() : new ArrayList<>(newData);
        List<ItemData> previous = filteredData;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                ItemData old = previous.get(oldPosition), item = next.get(newPosition);
                return old.getCaptureId() > 0 ? old.getCaptureId() == item.getCaptureId() : old == item;
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                ItemData old = previous.get(oldPosition), item = next.get(newPosition);
                return Objects.equals(old.getAppName(), item.getAppName())
                        && Objects.equals(old.getItem_from(), item.getItem_from())
                        && Objects.equals(old.getItem_data(), item.getItem_data())
                        && Objects.equals(old.getTimestamp(), item.getTimestamp())
                        && Objects.equals(old.getCategory(), item.getCategory());
            }
        }, false);
        mData = next;
        filteredData = next;
        diff.dispatchUpdatesTo(this);
    }

    public List<ItemData> getData() {
        return mData;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clearData() {
        mData.clear();
        filteredData.clear();
        notifyDataSetChanged();
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
