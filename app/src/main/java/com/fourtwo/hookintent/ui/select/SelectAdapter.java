package com.fourtwo.hookintent.ui.select;

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

public class SelectAdapter extends RecyclerView.Adapter<SelectAdapter.ViewHolder> {

    private List<Map<String, Object>> mData;

    public SelectAdapter(List<Map<String, Object>> data) {
        this.mData = data;
    }

    public void addItem(Map<String, Object> item) {
        this.mData.add(item);
        notifyItemInserted(mData.size() - 1);
    }

    public void removeItem(int position) {
        mData.remove(position);
        notifyItemRemoved(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_checkbox, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Map<String, Object> item = mData.get(position);
        holder.textView.setText((String) item.get("text"));

        holder.checkBox.setChecked((Boolean) item.get("type"));
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.put("type", isChecked);
        });

        holder.deleteButton.setOnClickListener(v -> removeItem(position));
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    public List<Map<String, Object>> getDataWithCheckStates() {
        return mData;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView textView;
        public CheckBox checkBox;
        public ImageButton deleteButton;

        public ViewHolder(View view) {
            super(view);
            textView = view.findViewById(R.id.text_view);
            checkBox = view.findViewById(R.id.check_box);
            deleteButton = view.findViewById(R.id.delete_button);
        }
    }
}
