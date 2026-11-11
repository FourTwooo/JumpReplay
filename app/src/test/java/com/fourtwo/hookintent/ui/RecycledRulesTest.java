package com.fourtwo.hookintent.ui;

import android.app.Application;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.adapter.DisabledActivityAdapter;
import com.fourtwo.hookintent.ui.select.SelectAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class RecycledRulesTest {
    private LinearLayout row() {
        Application context = RuntimeEnvironment.getApplication();
        LinearLayout row = new LinearLayout(context);
        TextView text = new TextView(context); text.setId(R.id.text_view); row.addView(text);
        CheckBox check = new CheckBox(context); check.setId(R.id.check_box); row.addView(check);
        ImageButton delete = new ImageButton(context); delete.setId(R.id.delete_button); row.addView(delete);
        return row;
    }
    private Map<String, Object> rule(String state, boolean enabled) {
        Map<String, Object> result = new HashMap<>(); result.put("text", "rule"); result.put(state, enabled); return result;
    }
    @Test public void rebindingFilterCheckboxDoesNotChangePreviousRecord() {
        Map<String, Object> first = rule("type", true), second = rule("type", false);
        SelectAdapter adapter = new SelectAdapter(new ArrayList<>(Arrays.asList(first, second)));
        SelectAdapter.ViewHolder holder = new SelectAdapter.ViewHolder(row());
        adapter.onBindViewHolder(holder, 0);
        adapter.onBindViewHolder(holder, 1);
        assertEquals(Boolean.TRUE, first.get("type"));
        holder.checkBox.setChecked(true);
        assertEquals(Boolean.TRUE, second.get("type"));
    }
    @Test public void rebindingDisabledCheckboxDoesNotChangePreviousRecord() {
        Map<String, Object> first = rule("open", true), second = rule("open", false);
        DisabledActivityAdapter adapter = new DisabledActivityAdapter(new ArrayList<>(Arrays.asList(first, second)));
        LinearLayout row = row();
        DisabledActivityAdapter.ViewHolder holder = new DisabledActivityAdapter.ViewHolder(row);
        adapter.onBindViewHolder(holder, 0);
        adapter.onBindViewHolder(holder, 1);
        assertEquals(Boolean.TRUE, first.get("open"));
        ((CheckBox) row.findViewById(R.id.check_box)).setChecked(true);
        assertEquals(Boolean.TRUE, second.get("open"));
    }
}
