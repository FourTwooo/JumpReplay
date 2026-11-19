package com.fourtwo.hookintent;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.fourtwo.hookintent.adapter.IntentInterceptAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class IntentEntryActivitiesTest {
    @Test public void emptyUriEditorDoesNotCrash() {
        try (ActivityController<IntentIntercept> controller = Robolectric.buildActivity(IntentIntercept.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("demo://host?q=a+b%26c"))).setup()) {
            EditText editor = controller.get().findViewById(R.id.url_text_view);
            assertNotNull(editor);
            editor.setText("");
            assertEquals("", editor.getText().toString());
        }
    }

    @Test public void interceptorListsAnExplicitTargetWithoutAnIntentFilter() {
        PackageInfo target = new PackageInfo();
        target.packageName = "example.match.target";
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = target.packageName;
        ActivityInfo entry = new ActivityInfo();
        entry.applicationInfo = target.applicationInfo;
        entry.packageName = target.packageName;
        entry.name = target.packageName + ".Entry";
        entry.exported = true;
        target.activities = new ActivityInfo[]{entry};
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(target);

        Intent incoming = new Intent().putExtra("DetailData",
                "intent:#Intent;component=" + entry.packageName + "/" + entry.name + ";end");
        try (ActivityController<IntentIntercept> controller = Robolectric.buildActivity(IntentIntercept.class,
                incoming).setup()) {
            RecyclerView list = controller.get().findViewById(R.id.recycler_view);
            IntentInterceptAdapter matches = (IntentInterceptAdapter) list.getAdapter();
            assertEquals(1, matches.getItemCount());
            IntentInterceptAdapter.ViewHolder row = matches.createViewHolder(list, matches.getItemViewType(0));
            matches.bindViewHolder(row, 0);
            TextView details = row.itemView.findViewById(R.id.app_details);
            assertEquals(entry.packageName + "\n" + entry.name, details.getText().toString());
        }
    }

    @Test public void newShortcutReplacesExistingParameterDialogAndKeepsLegacyProtocol() {
        Intent first = new Intent().putExtra("target_intent", "demo://host?q=VALUE")
                .putExtra("replace_params", new String[]{"VALUE"});
        try (ActivityController<ShortcutActivity> controller = Robolectric.buildActivity(ShortcutActivity.class, first).setup()) {
            assertFalse(controller.get().isFinishing());
            Intent second = new Intent().putExtra("target_intent", "demo://second?q=a+b%26c");
            controller.newIntent(second);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Intent launched = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull(launched);
            assertEquals("demo://second?q=a+b%26c", launched.getDataString());
            assertTrue(controller.get().isFinishing());
        }
    }
}
