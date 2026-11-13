package com.fourtwo.hookintent.actions;

import android.graphics.BitmapFactory;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.fourtwo.hookintent.R;
import java.util.*;

public final class ActionListAdapter extends RecyclerView.Adapter<ActionListAdapter.Holder> {
    public interface Listener {
        void open(QuickAction action);
        void run(QuickAction action);
        void options(QuickAction action);
        void reordered(List<String> ids);
    }
    private final boolean selecting;
    private final Listener listener;
    private final List<QuickAction> values=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private ItemTouchHelper drag;
    public ActionListAdapter(boolean selecting,Listener listener) { this.selecting=selecting; this.listener=listener; }
    public void submit(List<QuickAction> actions,List<String> checked) {
        values.clear(); values.addAll(actions);
        if(checked != null) { selected.clear(); selected.addAll(checked); }
        notifyDataSetChanged();
    }
    public List<String> ids(boolean selectedOnly) {
        List<String> ids=new ArrayList<>(); for(QuickAction action:values) if(!selectedOnly || selected.contains(action.id)) ids.add(action.id); return ids;
    }
    public void attach(RecyclerView view) {
        drag=new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP|ItemTouchHelper.DOWN,0) {
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override public boolean onMove(RecyclerView parent,RecyclerView.ViewHolder from,RecyclerView.ViewHolder to) {
                int a=from.getBindingAdapterPosition(),b=to.getBindingAdapterPosition();
                if(a < 0 || b < 0) return false;
                Collections.swap(values,a,b); notifyItemMoved(a,b); return true;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder holder,int direction) { }
            @Override public void clearView(RecyclerView parent,RecyclerView.ViewHolder holder) {
                super.clearView(parent,holder); listener.reordered(ids(false));
            }
        });
        drag.attachToRecyclerView(view);
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent,int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_quick_action,parent,false));
    }
    @Override public void onBindViewHolder(Holder holder,int position) {
        QuickAction action=values.get(position);
        holder.name.setText(action.name);
        holder.summary.setText((action.parameters.length > 0 ? action.parameters.length+" 个动态参数 · " : "")+action.template);
        if(action.icon == null) holder.icon.setImageResource(R.mipmap.ic_launcher);
        else holder.icon.setImageBitmap(BitmapFactory.decodeByteArray(action.icon,0,action.icon.length));
        holder.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        holder.check.setOnCheckedChangeListener(null); holder.check.setChecked(selected.contains(action.id));
        holder.check.setContentDescription("选择 "+action.name);
        holder.check.setOnCheckedChangeListener((button,checked) -> { if(checked) selected.add(action.id); else selected.remove(action.id); });
        holder.run.setVisibility(selecting ? View.GONE : View.VISIBLE);
        holder.run.setContentDescription("执行 "+action.name); holder.run.setOnClickListener(v -> listener.run(action));
        holder.itemView.setOnClickListener(v -> { if(selecting) holder.check.setChecked(!holder.check.isChecked()); else listener.open(action); });
        holder.itemView.setOnLongClickListener(v -> { if(!selecting) listener.options(action); return !selecting; });
        holder.drag.setOnTouchListener((v,event) -> { if(event.getActionMasked() == MotionEvent.ACTION_DOWN && drag != null) drag.startDrag(holder); return false; });
    }
    @Override public int getItemCount() { return values.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name,summary;
        final ImageView icon; final CheckBox check; final ImageButton run,drag;
        Holder(View view) {
            super(view); name=view.findViewById(R.id.action_name_text); summary=view.findViewById(R.id.action_summary);
            icon=view.findViewById(R.id.action_icon); check=view.findViewById(R.id.action_selected);
            run=view.findViewById(R.id.action_run); drag=view.findViewById(R.id.action_drag);
        }
    }
}
