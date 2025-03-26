package com.fourtwo.hookintent.ui.settings;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;

import java.util.List;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

    private final List<Pair<String, String>> categoryData;
    private final OnCategoryClickListener clickListener;
    private final OnCategoryLongClickListener longClickListener;

    public CategoryAdapter(List<Pair<String, String>> categoryData, OnCategoryClickListener clickListener, OnCategoryLongClickListener longClickListener) {
        this.categoryData = categoryData;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_horizontal_badge, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Pair<String, String> item = categoryData.get(position);
        String text = item.first;   // 标签文字
        String color = item.second; // 颜色值

        holder.textView.setText(text);

        try {
            GradientDrawable drawable = (GradientDrawable) holder.textView.getBackground();
            if (drawable != null) {
                int parsedColor = Color.parseColor(color);
                drawable.setColor(parsedColor);
            }
        } catch (IllegalArgumentException e) {
            Log.e("CategoryAdapter", "Invalid color: " + color);
            GradientDrawable drawable = (GradientDrawable) holder.textView.getBackground();
            if (drawable != null) {
                drawable.setColor(Color.GRAY); // 默认灰色
            }
        }

        // 点击事件 - 筛选逻辑
        holder.itemView.setOnClickListener(v -> {
//            有严重bug 取消 等下个版本修复 现在没时间了 就一个筛选功能 不重要
            if (clickListener != null) {
                clickListener.onCategoryClick(text);
            }
        });

        // 长按事件 - 删除或编辑
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onCategoryLongClick(text, position);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return categoryData.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.badge_text);
        }
    }

    public interface OnCategoryClickListener {
        void onCategoryClick(String category);
    }

    public interface OnCategoryLongClickListener {
        void onCategoryLongClick(String category, int position);
    }
}
