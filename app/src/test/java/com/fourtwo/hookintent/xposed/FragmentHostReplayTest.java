package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

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
import java.util.ArrayDeque;
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
public class FragmentHostReplayTest {
    @Test public void replaysOriginalHostMethodWithEditedValueAndPreservedExtras() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record("openProduct", true, false, 6000);
        assertTrue(FragmentHostReplay.handles(recorded));
        assertEquals("openProduct", recorded.getString("method"));
        assertEquals(Set.of("sku"), recorded.getBundle("arguments").keySet());

        fixture.returnToCatalog();
        fixture.host.renderDelayMillis = 6000;
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        fixture.engine.replay(recorded, arguments("new-sku"), failure -> {
            error.set(failure);
            done.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, fixture.host.calls);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));

        assertTrue(done.get());
        assertNull(error.get());
        assertTrue(fixture.host.animate);
        assertEquals(2, fixture.host.calls);
        assertEquals("new-sku", fixture.host.getFragmentManager().findFragmentByTag("current")
                .getArguments().getString("sku"));
        assertEquals((short) 13, fixture.host.received.get("aisle"));
        assertEquals(5L, fixture.host.received.get("revision"));
    }

    @Test public void rejectsAnArgumentMatchThatAlsoBelongsToAnotherObservedCall() throws Exception {
        Fixture fixture = new Fixture();
        assertNull(fixture.record("openVariant", false, true, 0));
        assertEquals(1, fixture.host.calls);
    }

    @Test public void rejectsWrongSourceAndUnconfirmedEditedValue() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record("openVariant", false, false, 0);
        assertNotNull(recorded);
        fixture.publish();
        AtomicReference<Exception> failure = new AtomicReference<>();
        fixture.engine.replay(recorded, arguments("new-sku"), failure::set);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));
        assertNotNull(failure.get());
        assertEquals(1, fixture.host.calls);

        fixture.returnToCatalog();
        fixture.host.ignoreEditedValue = true;
        failure.set(null);
        fixture.engine.replay(recorded, arguments("new-sku"), failure::set);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertNotNull(failure.get());
        assertEquals(2, fixture.host.calls);
    }

    private static Bundle arguments(String value) {
        Bundle result = new Bundle();
        result.putString("sku", value);
        return result;
    }

    public static class CatalogFragment extends Fragment {}
    public static class ProductFragment extends Fragment {}

    public static class ShopActivity extends Activity {
        int calls;
        boolean animate;
        boolean ignoreEditedValue;
        long renderDelayMillis;
        Bundle received;

        @Override public boolean hasWindowFocus() { return true; }

        public void openProduct(String sku, Bundle options, boolean transitions) {
            animate = transitions;
            showProduct(sku, options);
        }

        public void openVariant(String sku, Bundle options) {
            showProduct(sku, options);
        }

        private void showProduct(String sku, Bundle options) {
            calls++;
            received = options;
            ProductFragment product = new ProductFragment();
            product.setArguments(arguments(ignoreEditedValue ? "original-sku" : sku));
            if (renderDelayMillis == 0) show(product);
            else new Handler(Looper.getMainLooper()).postDelayed(() -> show(product), renderDelayMillis);
        }

        private void show(Fragment fragment) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, fragment, "current").commitNow();
        }
    }

    private static final class Fixture {
        final ShopActivity host = Robolectric.buildActivity(ShopActivity.class).setup().get();
        final FragmentHostReplay engine = new FragmentHostReplay(new Handler(Looper.getMainLooper()),
                ignored -> true, () -> List.of(host), ignored -> publish());

        Fixture() { returnToCatalog(); }

        void returnToCatalog() {
            host.show(new CatalogFragment());
            publish();
        }

        void publish() {
            Fragment visible = host.getFragmentManager().findFragmentByTag("current");
            Bundle arguments = visible.getArguments();
            Map<String, Object> values = arguments == null ? Map.of() : Map.of("sku", arguments.getString("sku"));
            engine.observe(host, Map.of(visible.getClass().getName(), List.of(values)));
        }

        Bundle record(String method, boolean transitions, boolean ambiguous, long renderDelay) throws Exception {
            String sku = "original-sku";
            Bundle extras = new Bundle();
            extras.putShort("aisle", (short) 13);
            extras.putLong("revision", 5L);

            // Feed the Xposed boundary while exercising actual Fragment commits and host method replay.
            stage(method, sku, extras, transitions ? Boolean.TRUE : null);
            if (ambiguous) stage("openProduct", sku, extras, true);
            host.renderDelayMillis = renderDelay;
            if (transitions) host.openProduct(sku, extras, true);
            else host.openVariant(sku, extras);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(renderDelay));
            publish();
            return engine.capture(host, ProductFragment.class.getName(), Map.of("sku", sku));
        }

        @SuppressWarnings("unchecked")
        private void stage(String methodName, String value, Bundle extras, Boolean option) throws Exception {
            Field states = FragmentHostReplay.class.getDeclaredField("states");
            states.setAccessible(true);
            Object state = ((Map<?, ?>) states.get(engine)).get(host);
            Field calls = state.getClass().getDeclaredField("calls");
            calls.setAccessible(true);
            Field source = state.getClass().getDeclaredField("source");
            source.setAccessible(true);
            Method encode = FragmentHostReplay.class.getDeclaredMethod("encode", Bundle.class);
            encode.setAccessible(true);
            Method target = option == null ? ShopActivity.class.getDeclaredMethod(methodName, String.class, Bundle.class)
                    : ShopActivity.class.getDeclaredMethod(methodName, String.class, Bundle.class, boolean.class);
            Class<?> call = Class.forName(FragmentHostReplay.class.getName() + "$Call");
            Constructor<?> make = call.getDeclaredConstructor(Method.class, String.class, Set.class,
                    String.class, Boolean.class, String.class, boolean.class);
            make.setAccessible(true);
            ((ArrayDeque<Object>) calls.get(state)).add(make.newInstance(target, source.get(state),
                    Set.of(CatalogFragment.class.getName()), value, option, encode.invoke(null, extras), true));
        }
    }
}
