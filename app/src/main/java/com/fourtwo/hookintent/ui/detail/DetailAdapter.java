package com.fourtwo.hookintent.ui.detail;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;

import java.util.List;

public class DetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_SEPARATOR = 1;

    private final List<Pair<String, String>> dataList;

    public DetailAdapter(List<Pair<String, String>> dataList) {
        this.dataList = dataList;
    }

    @Override
    public int getItemViewType(int position) {
        Pair<String, String> item = dataList.get(position);
        if ("separator".equals(item.first)) {
            return TYPE_SEPARATOR;
        } else {
            return TYPE_NORMAL;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SEPARATOR) {
            View view = new View(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    1  // Set height for the separator
            ));
            view.setBackgroundResource(android.R.color.darker_gray);  // Set a different color for separator
            return new SeparatorViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.detail_item_data, parent, false);
            return new DataViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == TYPE_NORMAL) {
            Pair<String, String> data = dataList.get(position);
            DataViewHolder dataHolder = (DataViewHolder) holder;
            dataHolder.keyTextView.setText(data.first);
            dataHolder.valueTextView.setText(data.second);

            // Set long press listener for copying data
            dataHolder.itemView.setOnLongClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
                builder.setTitle("选择要复制的内容")
                        .setItems(new String[]{"复制 Key", "复制 Value"}, (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                String textToCopy = which == 0 ? data.first : data.second;
                                ClipData clip = ClipData.newPlainText("Detail Data", textToCopy);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(v.getContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        });
                builder.show();
                return true;
            });

            // Set touch listener for touch feedback
            dataHolder.itemView.setOnTouchListener(new View.OnTouchListener() {

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int normalColor = Color.WHITE;
                    int pressedColor = Color.LTGRAY;
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startColorAnimation(dataHolder.itemView, normalColor, pressedColor);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            startColorAnimation(dataHolder.itemView, pressedColor, normalColor);
                            break;
                    }
                    return false;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    static class DataViewHolder extends RecyclerView.ViewHolder {
        TextView keyTextView;
        TextView valueTextView;

        public DataViewHolder(@NonNull View itemView) {
            super(itemView);
            keyTextView = itemView.findViewById(R.id.keyTextView);
            valueTextView = itemView.findViewById(R.id.valueTextView);
        }
    }

    static class SeparatorViewHolder extends RecyclerView.ViewHolder {
        public SeparatorViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private void startColorAnimation(View view, int startColor, int endColor) {
        ValueAnimator animator = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            animator = ValueAnimator.ofArgb(startColor, endColor);
        }
        assert animator != null;
        animator.setDuration(300); // Animation duration in milliseconds
        animator.addUpdateListener(animation -> view.setBackgroundColor((int) animation.getAnimatedValue()));
        animator.start();
    }
}
