package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentTransactionReplayTest {
    @Test public void replaysActualAndroidXReplaceWithEditedArgumentsAndBackStack() throws Exception {
        FixtureActivity host = Robolectric.buildActivity(FixtureActivity.class).setup().get();
        FragmentManager manager = host.getSupportFragmentManager();
        FragmentTransactionReplay engine = new FragmentTransactionReplay(new Handler(Looper.getMainLooper()),
                activity -> activity == host, () -> List.of(host), ignored -> {
                    Set<Object> visible = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Fragment fragment : manager.getFragments()) {
                        if (fragment.getView() != null && fragment.getView().isAttachedToWindow()) visible.add(fragment);
                    }
                    // This is the same identity-based visibility input supplied by the live collector.
                    host.engine.observe(host, visible);
                });
        host.engine = engine;

        // Robolectric cannot execute Xposed hooks. Drive the hook's snapshot path on a real AndroidX transaction.
        Object api = installWithoutXposed(engine, host.getClassLoader());
        Bundle original = new Bundle();
        original.putString("title", "initial");
        original.putLong("quantity", 3L);
        Detail detail = new Detail();
        detail.setArguments(original);
        FragmentTransaction transaction = manager.beginTransaction().setReorderingAllowed(true)
                .replace(FixtureActivity.CONTAINER, detail, "detail")
                .addToBackStack("from_catalog");
        Method snapshot = FragmentTransactionReplay.class.getDeclaredMethod("snapshot",
                api.getClass(), Object.class, String.class);
        snapshot.setAccessible(true);
        Object recording = snapshot.invoke(engine, api, transaction, "commit");
        assertNotNull(recording);
        Bundle descriptor = (Bundle) get(recording, "descriptor");
        assertTrue(FragmentTransactionReplay.handles(descriptor));
        assertTrue(descriptor.getBoolean("reorder"));
        assertEquals("from_catalog", descriptor.getString("back_stack_name"));
        transaction.commit();
        manager.executePendingTransactions();
        assertTrue(manager.popBackStackImmediate());

        Bundle edited = new Bundle();
        edited.putString("title", "edited");
        // Persisting a small Long in the JSON archive may decode it as Integer.
        edited.putInt("quantity", 5);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        engine.replay(descriptor, edited, error -> {
            failure.set(error);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));

        assertTrue(completed.get());
        assertNull(failure.get());
        Fragment view = manager.findFragmentByTag("detail");
        assertNotNull(view);
        assertEquals("edited:5", ((TextView) view.requireView()).getText().toString());
        assertEquals(5L, view.requireArguments().get("quantity"));
        assertEquals("from_catalog", manager.getBackStackEntryAt(0).getName());
        assertTrue(manager.popBackStackImmediate());
        assertTrue(manager.findFragmentById(FixtureActivity.CONTAINER) instanceof Catalog);
    }

    @SuppressWarnings("unchecked")
    private static Object installWithoutXposed(FragmentTransactionReplay engine, ClassLoader loader) throws Exception {
        Class<?> apiClass = Class.forName(FragmentTransactionReplay.class.getName() + "$Api");
        Constructor<?> constructor = apiClass.getDeclaredConstructor(ClassLoader.class, String.class, boolean.class);
        constructor.setAccessible(true);
        Object api = constructor.newInstance(loader, "androidx.fragment.app", true);
        ((Set<ClassLoader>) get(engine, "scanned")).add(loader);
        ((Map<Class<?>, Object>) get(engine, "installed")).put((Class<?>) get(api, "record"), api);
        return api;
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    public static class FixtureActivity extends FragmentActivity {
        static final int CONTAINER = 0x01030001;
        FragmentTransactionReplay engine;

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout container = new FrameLayout(this);
            container.setId(CONTAINER);
            setContentView(container);
            if (state == null) {
                getSupportFragmentManager().beginTransaction().add(CONTAINER, new Catalog()).commitNow();
            }
        }

        @Override public boolean hasWindowFocus() { return true; }
    }

    public static class Catalog extends Fragment {
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
            return new View(requireContext());
        }
    }

    public static class Detail extends Fragment {
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
            TextView text = new TextView(requireContext());
            Bundle args = requireArguments();
            text.setText(args.getString("title") + ":" + args.getLong("quantity"));
            return text;
        }
    }
}
