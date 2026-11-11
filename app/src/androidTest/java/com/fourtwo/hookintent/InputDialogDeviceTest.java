package com.fourtwo.hookintent;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.view.Menu;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InputDialogDeviceTest {
    @Test public void schemeDialogOpensAndInlineRegexSwitchWorks() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.accessibilityservice.AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(service);
        try (ActivityScenario<DisabledActivity> scenario = ActivityScenario.launch(DisabledActivity.class)) {
            scenario.onActivity(activity -> {
                Menu menu = new PopupMenu(activity, new View(activity)).getMenu();
                assertTrue(activity.onOptionsItemSelected(menu.add(0, R.id.add_text, 0, "Add")));
            });
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(100, 5000);
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertNotNull(root);
            String id = instrumentation.getTargetContext().getPackageName() + ":id/text_input_end_icon";
            List<AccessibilityNodeInfo> icons = root.findAccessibilityNodeInfosByViewId(id);
            assertEquals(1, icons.size());
            assertEquals(instrumentation.getTargetContext().getString(R.string.input_regex_off), icons.get(0).getContentDescription().toString());
            assertTrue(icons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(100, 5000);
            root = instrumentation.getUiAutomation().getRootInActiveWindow();
            icons = root.findAccessibilityNodeInfosByViewId(id);
            assertEquals(instrumentation.getTargetContext().getString(R.string.input_regex_on), icons.get(0).getContentDescription().toString());
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), "input-scheme.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        }
    }
}
