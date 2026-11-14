package com.fourtwo.hookintent;

import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fourtwo.hookintent.actions.ActionEditorActivity;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ActionDraftDeviceTest {
    private Set<String> files(Context context,String directory) {
        String[] values=new File(context.getFilesDir(),directory).list();
        return values==null ? new HashSet<>() : new HashSet<>(Arrays.asList(values));
    }
    private void ready(ActivityScenario<ActionEditorActivity> scenario) throws Exception {
        AtomicBoolean ready=new AtomicBoolean();
        for(int i=0;i<100;i++) {
            scenario.onActivity(a -> ready.set(a.findViewById(R.id.submit_button).isEnabled()));
            if(ready.get()) return;Thread.sleep(50);
        }
        fail("Action editor did not load");
    }
    @Test public void repeatedRecreationRestoresFormAndCancelRemovesOnlyItsDraft() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> before=files(context,"action_drafts"),shortcuts=files(context,"shortcut_payloads");
        Set<String> created=new HashSet<>();
        try(ActivityScenario<ActionEditorActivity> scenario=ActivityScenario.launch(new Intent(context,ActionEditorActivity.class)
                .putExtra("initial_uri","demo://draft?value=hello"))) {
            ready(scenario);
            for(int i=0;i<3;i++) {
                String expectedName="QA draft recovery "+i,expectedUri="demo://draft?value="+i;
                scenario.onActivity(a -> {
                    ((EditText)a.findViewById(R.id.shortcut_name)).setText(expectedName);
                    ((EditText)a.findViewById(R.id.intent_string)).setText(expectedUri);
                });
                scenario.recreate();ready(scenario);
                scenario.onActivity(a -> {
                    assertEquals(expectedName,((EditText)a.findViewById(R.id.shortcut_name)).getText().toString());
                    assertEquals(expectedUri,((EditText)a.findViewById(R.id.intent_string)).getText().toString());
                });
                created=files(context,"action_drafts");created.removeAll(before);assertEquals(1,created.size());
                assertEquals(shortcuts,files(context,"shortcut_payloads"));
            }
            scenario.onActivity(a -> a.findViewById(R.id.cancel_button).performClick());
        }
        for(int i=0;i<100;i++) {
            Set<String> remaining=files(context,"action_drafts");remaining.retainAll(created);
            if(remaining.isEmpty()) break;Thread.sleep(50);
        }
        Set<String> remaining=files(context,"action_drafts");remaining.retainAll(created);assertTrue(remaining.isEmpty());
        assertEquals(shortcuts,files(context,"shortcut_payloads"));
    }
}
