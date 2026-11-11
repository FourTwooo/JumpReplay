package com.fourtwo.hookintent;

import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.navigation.Navigation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fourtwo.hookintent.data.ItemData;
import com.google.android.material.tabs.TabLayout;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the real navigation, long-content copy and DayNight resources without adding capture records. */
@RunWith(AndroidJUnit4.class)
public class ReadabilityDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private static final String URL = "demo://example.org/article/detail?source=com.example.reader&redirect=https%3A%2F%2Fexample.org%2Fdocuments%2Fandroid%2Fintent%3Fpage%3D1%26language%3Dzh-CN";
    private static final String STACK = "java.lang.Throwable: Captured intent\n"
            + "    at com.example.reader.navigation.IntentRouter.openArticle(IntentRouter.java:128)\n"
            + "    at com.example.reader.article.ArticleDetailActivity.onClick(ArticleDetailActivity.java:256)\n"
            + "    at android.view.View.performClick(View.java:7259)\n"
            + "    at android.app.ActivityThread.main(ActivityThread.java:7356)";

    @Test public void detailsCopyFullContentInBothThemesAndEmptySearchKeepsItsHint() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (boolean night : new boolean[]{false, true}) {
                scenario.onActivity(activity -> activity.getDelegate().setLocalNightMode(night
                        ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO));
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    Bundle fields = new Bundle();
                    fields.putString("FunctionCall", "startActivity(Intent, Bundle)");
                    fields.putString("packageName", "com.example.reader");
                    fields.putString("component", "com.example.reader.article.ArticleDetailActivity");
                    fields.putString("scheme_raw_url", URL);
                    ItemData item = new ItemData(null, "Preview", URL, "", "", "", fields, "Intent", STACK, URL);
                    Bundle arguments = new Bundle(); arguments.putParcelable("itemData", item);
                    Navigation.findNavController(activity, R.id.nav_host_fragment_content_main).navigate(R.id.nav_detail, arguments);
                });
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    TextView summary = activity.findViewById(R.id.urlTextView);
                    assertEquals(URL, summary.getText().toString());
                    assertTrue(summary.performLongClick());
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    assertEquals(URL, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    View content = summary.getRootView().findViewById(R.id.contentFrame);
                    int color = androidx.core.content.ContextCompat.getColor(activity, R.color.screen_bg);
                    assertEquals(night, androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.1);
                    assertTrue(content.getHeight() > 0);
                });
                screenshot(night ? "detail-night" : "detail-day");
                scenario.onActivity(activity -> ((androidx.recyclerview.widget.RecyclerView)
                        activity.findViewById(R.id.dataRecyclerView)).scrollToPosition(3));
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.dataRecyclerView);
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(3);
                    assertNotNull(holder);
                    TextView value = holder.itemView.findViewById(R.id.valueTextView);
                    assertEquals(URL, value.getText().toString());
                    assertTrue(value.getLineCount() > 1);
                });
                scenario.onActivity(activity -> {
                    ((TabLayout)activity.findViewById(R.id.tabLayout)).getTabAt(1).select();
                    TextView stack = activity.findViewById(R.id.tabTextView);
                    assertEquals(STACK, stack.getText().toString());
                    assertTrue(stack.performLongClick());
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    assertEquals(STACK, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(stack.getCurrentTextColor(),
                            ((ColorDrawable)stack.getBackground()).getColor()) > 4.5);
                });
                screenshot(night ? "stack-night" : "stack-day");
            }
            scenario.onActivity(activity -> {
                Navigation.findNavController(activity, R.id.nav_host_fragment_content_main).navigate(R.id.nav_home);
            });
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> ((EditText)activity.findViewById(R.id.searchEditText)).setText("__ui_readability_no_match_9be1__"));
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
            while (!visible.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> visible.set(activity.findViewById(R.id.empty_state).getVisibility() == View.VISIBLE));
                if (!visible.get()) android.os.SystemClock.sleep(50);
            }
            assertTrue(visible.get());
            scenario.onActivity(activity -> {
                assertEquals(activity.getString(R.string.home_empty_search_title),
                        ((TextView)activity.findViewById(R.id.empty_title)).getText().toString());
                assertFalse(activity.findViewById(R.id.searchEditText).hasFocus());
            });
            screenshot("home-night");
            scenario.onActivity(activity -> {
                ((EditText)activity.findViewById(R.id.searchEditText)).setText("");
                Navigation.findNavController(activity, R.id.nav_host_fragment_content_main).navigate(R.id.nav_setup);
            });
            instrumentation.waitForIdleSync();
            screenshot("setup-night");
            scenario.onActivity(activity -> activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        }
    }

    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        // Transient clipboard / Root toasts must not obscure the layout being reviewed.
        android.os.SystemClock.sleep(2400);
        instrumentation.getUiAutomation().waitForIdle(200, 5000);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream stream = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }
}
