package com.fourtwo.hookintent.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;

import java.util.List;
import java.util.Map;

public class DisabledActivityAdapter extends RecyclerView.Adapter<DisabledActivityAdapter.ViewHolder> {

    private final List<Map<String, Object>> dataList;

    // 构造函数
    public DisabledActivityAdapter(List<Map<String, Object>> dataList) {
        this.dataList = dataList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_checkbox, parent, false);
        return new ViewHolder(view);
    }

    public void addItem(Map<String, Object> item) {
        this.dataList.add(item);
        notifyItemInserted(dataList.size() - 1);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Map<String, Object> item = dataList.get(position);

        // 设置文本和状态
        holder.textView.setText((String) item.get("text"));
        holder.checkBox.setChecked((boolean) item.get("open"));

        // 设置复选框点击事件
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 更新 dataList 中的状态
            item.put("open", isChecked);
            // 如果需要额外处理，可以添加回调或者触发其他逻辑
        });

        // 设置删除按钮点击事件
        holder.deleteButton.setOnClickListener(v -> {
            // 删除对应项
            dataList.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, dataList.size());
        });
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    // ViewHolder
    public static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;
        TextView textView;
        ImageButton deleteButton;

        public ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.check_box);
            textView = itemView.findViewById(R.id.text_view);
            deleteButton = itemView.findViewById(R.id.delete_button);
        }
    }
}
