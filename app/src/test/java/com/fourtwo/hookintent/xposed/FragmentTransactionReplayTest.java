package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Intent;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentTransactionReplayTest {
    @Test public void replaysLegacySingleReplaceWithoutAnOperationList() throws Exception {
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
        descriptor.remove("operations"); // A descriptor saved by an earlier build stores one ADD/REPLACE.
        transaction.commit();
        manager.executePendingTransactions();
        assertTrue(manager.popBackStackImmediate());

        Bundle edited = new Bundle();
        edited.putString("title", "edited");
        // Persisting a small Long in the JSON archive may decode it as Integer.
        edited.putInt("quantity", 5);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        engine.replay(descriptor, edited, InAppNavigationCapture.SOURCE_READY_MS, error -> {
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

    @Test public void replaysMultiOperationTransactionAndRestoresOriginalNavigation() throws Exception {
        FixtureActivity host = Robolectric.buildActivity(FixtureActivity.class).setup().get();
        FragmentManager manager = host.getSupportFragmentManager();
        Catalog catalog = (Catalog) manager.findFragmentById(FixtureActivity.CONTAINER);
        FragmentTransactionReplay replay = new FragmentTransactionReplay(new Handler(Looper.getMainLooper()),
                activity -> activity == host, () -> List.of(host), ignored -> {
                    Set<Object> visible = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Fragment fragment : manager.getFragments()) {
                        if (!fragment.isHidden() && fragment.getView() != null
                                && fragment.getView().isAttachedToWindow()) visible.add(fragment);
                    }
                    host.engine.observe(host, visible);
                });
        host.engine = replay;
        Object api = installWithoutXposed(replay, host.getClassLoader());
        Detail detail = new Detail();
        Bundle original = new Bundle();
        original.putString("title", "initial");
        original.putLong("quantity", 3L);
        detail.setArguments(original);

        FragmentTransaction originalCall = manager.beginTransaction().hide(catalog)
                .add(FixtureActivity.CONTAINER, detail, "detail")
                .setPrimaryNavigationFragment(detail)
                .addToBackStack("show_detail");
        Object record = snapshot(replay, api, originalCall, "commit");
        assertNull(get(record, "failure"));
        originalCall.commit();
        manager.executePendingTransactions();
        recordCompletion(replay, api, record);
        Bundle descriptor = replay.capture(host, detail);
        assertNotNull(descriptor);
        assertNull(replay.captureFailure(host, detail));
        assertTrue(manager.popBackStackImmediate());

        Bundle edited = new Bundle();
        edited.putString("title", "changed");
        edited.putLong("quantity", 7L);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        replay.replay(descriptor, edited, InAppNavigationCapture.SOURCE_READY_MS, error -> {
            failure.set(error);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));

        assertTrue(completed.get());
        assertNull(failure.get());
        Fragment target = manager.findFragmentByTag("detail");
        assertSame(target, manager.getPrimaryNavigationFragment());
        assertTrue(catalog.isHidden());
        assertEquals("changed:7", ((TextView) target.requireView()).getText().toString());
        assertTrue(manager.popBackStackImmediate());
        assertFalse(catalog.isHidden());
        assertNull(manager.getPrimaryNavigationFragment());
    }

    @Test public void storesSpecificReasonsForOpaqueArgumentsAndShowingExistingFragment() throws Exception {
        FixtureActivity host = Robolectric.buildActivity(FixtureActivity.class).setup().get();
        FragmentManager manager = host.getSupportFragmentManager();
        FragmentTransactionReplay replay = new FragmentTransactionReplay(new Handler(Looper.getMainLooper()),
                activity -> activity == host, () -> List.of(host), ignored -> {});
        Object api = installWithoutXposed(replay, host.getClassLoader());
        Fragment catalog = manager.findFragmentById(FixtureActivity.CONTAINER);
        manager.beginTransaction().hide(catalog).commitNow();

        FragmentTransaction show = manager.beginTransaction().show(catalog);
        Object existing = snapshot(replay, api, show, "commitNow");
        show.commitNow();
        recordCompletion(replay, api, existing);
        assertNull(replay.capture(host, catalog));
        assertEquals("transaction_existing_destination", replay.captureFailure(host, catalog));

        Detail opaque = new Detail();
        Bundle unsupported = new Bundle();
        unsupported.putParcelable("custom_state", new Intent());
        opaque.setArguments(unsupported);
        FragmentTransaction add = manager.beginTransaction().add(FixtureActivity.CONTAINER, opaque);
        Object unsupportedRecord = snapshot(replay, api, add, "commitNow");
        add.commitNow();
        recordCompletion(replay, api, unsupportedRecord);
        assertNull(replay.capture(host, opaque));
        assertEquals("transaction_unsupported_arguments", replay.captureFailure(host, opaque));
    }

    private static Object snapshot(FragmentTransactionReplay engine, Object api, FragmentTransaction transaction,
            String commit) throws Exception {
        Method method = FragmentTransactionReplay.class.getDeclaredMethod("snapshot",
                api.getClass(), Object.class, String.class);
        method.setAccessible(true);
        return method.invoke(engine, api, transaction, commit);
    }

    private static void recordCompletion(FragmentTransactionReplay engine, Object api, Object record) throws Exception {
        Method method = FragmentTransactionReplay.class.getDeclaredMethod("recordCompletion",
                api.getClass(), record.getClass(), boolean.class);
        method.setAccessible(true);
        method.invoke(engine, api, record, false);
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
