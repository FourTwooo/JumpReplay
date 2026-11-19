package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33,
        instrumentedPackages = "de.robv.android.xposed", shadows = ViewPagerReplayTest.TestHookBridge.class)
public class ViewPagerReplayTest {
    @Before public void clearHooks() {
        TestHookBridge.hooks.clear();
    }

    @Test public void capturesOneActualSelectionAcrossNestedOverloadsAndReplaysAnEditedIndex() throws Exception {
        Fixture fixture = new Fixture();
        Bundle record = fixture.captureSelection();
        assertEquals("setCurrentItem", record.getString("method"));
        assertEquals(Pager.class.getName(), record.getString("setter_owner"));
        assertFalse(record.containsKey("smooth_scroll"));
        assertEquals(0, record.getInt("source_position", -1));
        assertEquals(3, record.getInt("page_count"));
        assertEquals(1, record.getBundle("arguments").getInt(ViewPagerReplay.POSITION, -1));

        fixture.capturing = false;
        fixture.engine.forget(fixture.activity);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();
        fixture.engine.replay(record, position(2), failure -> {
            error.set(failure);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(completed.get());
        assertNull(error.get());
        assertEquals(2, fixture.pager.getCurrentItem());
        assertEquals(2, fixture.pager.publicSelections);
        assertEquals(2, fixture.pager.actualSelections);
    }

    @Test public void rejectsAnEditedIndexOutsideTheAdapterBounds() throws Exception {
        Fixture fixture = new Fixture();
        Bundle record = fixture.captureSelection();
        AtomicReference<Exception> error = new AtomicReference<>();
        fixture.engine.replay(record, position(3), error::set);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(error.get());
        assertEquals(1, fixture.pager.getCurrentItem());
        assertEquals(1, fixture.pager.actualSelections);
    }

    @Test public void rejectsAChangedAdapterCount() throws Exception {
        Fixture fixture = new Fixture();
        Bundle record = fixture.captureSelection();
        fixture.pager.getAdapter().count = 4;
        AtomicReference<Exception> error = new AtomicReference<>();
        fixture.engine.replay(record, position(2), error::set);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));

        assertNotNull(error.get());
        assertEquals(1, fixture.pager.getCurrentItem());
        assertEquals(1, fixture.pager.actualSelections);
    }

    private static Bundle position(int position) {
        Bundle result = new Bundle();
        result.putInt(ViewPagerReplay.POSITION, position);
        return result;
    }

    public static class FocusedActivity extends Activity {
        @Override public boolean hasWindowFocus() { return true; }
    }

    public static class PagerBase extends FrameLayout {
        private final Adapter adapter = new Adapter();
        private int current;
        int actualSelections;

        PagerBase(Context context) { super(context); }

        public int getCurrentItem() { return current; }
        public Adapter getAdapter() { return adapter; }

        public void setCurrentItem(int position, boolean smooth) {
            current = position;
            actualSelections++;
        }
    }

    public static class Pager extends PagerBase {
        int publicSelections;

        Pager(Context context) { super(context); }

        public void setCurrentItem(int position) {
            publicSelections++;
            setCurrentItem(position, true);
        }
    }

    public static class Adapter {
        int count = 3;

        public int getCount() { return count; }
    }

    @Implements(value = XposedBridge.class, isInAndroidSdk = false)
    public static class TestHookBridge {
        static final Map<Member, XC_MethodHook> hooks = new HashMap<>();

        @Implementation protected static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook hook) {
            hooks.put(method, hook);
            return null;
        }
    }

    private static final class Fixture {
        final FocusedActivity activity = Robolectric.buildActivity(FocusedActivity.class).setup().get();
        final Pager pager = new Pager(activity);
        final List<Bundle> captured = new ArrayList<>();
        boolean capturing = true;
        final ViewPagerReplay engine = new ViewPagerReplay(new Handler(Looper.getMainLooper()),
                owner -> capturing && owner == activity, () -> List.of(activity),
                (owner, view, before, after, record) -> captured.add(record));

        Fixture() {
            FrameLayout container = new FrameLayout(activity);
            container.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(container);
            View decor = activity.getWindow().getDecorView();
            int dimension = View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY);
            decor.measure(dimension, dimension);
            decor.layout(0, 0, 500, 500);
        }

        Bundle captureSelection() throws Exception {
            engine.scan(activity);
            Method primary = Pager.class.getMethod("setCurrentItem", int.class);
            Method nested = PagerBase.class.getMethod("setCurrentItem", int.class, boolean.class);
            XC_MethodHook.MethodHookParam enterPrimary = before(primary, 1);
            XC_MethodHook.MethodHookParam enterNested = before(nested, 1, true);
            pager.setCurrentItem(1);
            callback("afterHookedMethod", enterNested);
            callback("afterHookedMethod", enterPrimary);
            assertEquals(1, captured.size());
            return captured.get(0);
        }

        private XC_MethodHook.MethodHookParam before(Method method, Object... arguments) throws Exception {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = method;
            param.thisObject = pager;
            param.args = arguments;
            callback("beforeHookedMethod", param);
            return param;
        }

        private void callback(String phase, XC_MethodHook.MethodHookParam param) throws Exception {
            XC_MethodHook hook = TestHookBridge.hooks.get(param.method);
            assertNotNull(hook);
            Method callback = XC_MethodHook.class.getDeclaredMethod(phase, XC_MethodHook.MethodHookParam.class);
            callback.setAccessible(true);
            callback.invoke(hook, param);
        }
    }
}
