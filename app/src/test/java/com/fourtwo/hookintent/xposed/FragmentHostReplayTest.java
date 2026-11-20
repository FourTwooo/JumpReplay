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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentHostReplayTest {
    @Test public void replaysInheritedNonVoidMethodWithMultipleTypedEdits() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record(false, false, 6000);
        assertTrue(FragmentHostReplay.handles(recorded));
        assertEquals("openProduct", recorded.getString("method"));
        Bundle edited = new Bundle(recorded.getBundle("arguments"));
        assertEquals(Set.of("sku", "quantity", "motion", "region", "aisle"), edited.keySet());

        fixture.returnToCatalog();
        fixture.host.renderDelayMillis = 6000;
        edited.putString("sku", "new-sku");
        edited.putInt("quantity", 37); // The persisted JSON may decode a small Long as an Integer.
        edited.putBoolean("motion", false);
        edited.putString("region", "west");
        edited.putInt("aisle", 29);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        fixture.engine.replay(recorded, edited, InAppNavigationCapture.SOURCE_READY_MS, failure -> {
            error.set(failure);
            done.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, fixture.host.calls);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));

        assertTrue(done.get());
        assertNull(error.get());
        Bundle rendered = fixture.host.getFragmentManager().findFragmentByTag("current").getArguments();
        assertEquals("new-sku", rendered.getString("sku"));
        assertEquals(37L, rendered.get("quantity"));
        assertEquals(false, rendered.getBoolean("motion"));
        assertEquals("west", rendered.getString("region"));
        assertEquals((short)29, fixture.host.received.get("aisle"));
        assertTrue(fixture.host.received.containsKey("unused"));
        assertNull(fixture.host.received.get("unused"));
    }

    @Test public void recordsSpecificReasonForUnrelatedMatchingCalls() throws Exception {
        Fixture fixture = new Fixture();
        assertNull(fixture.record(true, false, 0));
        assertEquals("host_call_ambiguous",
                fixture.engine.captureFailure(fixture.host, ProductFragment.class.getName()));
    }

    @Test public void evictedNamedAndPositionalNumbersDoNotHideStrongerTextEvidence() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stageCounter(11L);
        fixture.stageNamedCounter(11L);
        for (int index = 0; index < 150; index++) {
            fixture.stageCounter(1000L + index);
            fixture.stageNamedCounter(1000L + index);
        }
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        assertEquals("openProduct", recorded.getString("method"));
        assertNull(fixture.engine.captureFailure(fixture.host, ProductFragment.class.getName()));
    }

    @Test public void aNoisyStringMethodDoesNotEvictAnotherMethodsObservedCall() throws Exception {
        Fixture fixture = new Fixture();
        fixture.trailingStringCalls = 150;
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        assertEquals("openProduct", recorded.getString("method"));
    }

    @Test public void repeatedIdenticalInvocationsProduceOneReplayRecipe() throws Exception {
        Fixture fixture = new Fixture();
        fixture.repeatedProductCalls = 150;
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        assertEquals("openProduct", recorded.getString("method"));
    }

    @Test public void rejectsReplayIfACompetingStringWitnessWasDiscarded() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stageOtherProduct("original-sku");
        for (int index = 0; index < 150; index++) fixture.stageOtherProduct("unrelated-" + index);
        assertNull(fixture.record(false, false, 0));
        assertEquals("host_candidate_overflow",
                fixture.engine.captureFailure(fixture.host, ProductFragment.class.getName()));
    }

    @Test public void recordsSpecificReasonWhenOneInputCannotBePreserved() throws Exception {
        Fixture fixture = new Fixture();
        assertNull(fixture.record(false, true, 0));
        assertEquals("host_arguments_unsupported",
                fixture.engine.captureFailure(fixture.host, ProductFragment.class.getName()));
    }

    @Test public void replaysAnotherDestinationOfTheSameClass() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        Bundle edited = new Bundle(recorded.getBundle("arguments"));
        edited.putString("sku", "new-sku");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        fixture.engine.replay(recorded, edited, InAppNavigationCapture.SOURCE_READY_MS, error -> {
            failure.set(error);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(completed.get());
        assertNull(failure.get());
        assertEquals(2, fixture.host.calls);
        assertEquals("new-sku", fixture.host.getFragmentManager().findFragmentByTag("current")
                .getArguments().getString("sku"));
    }

    @Test public void rejectsUnconfirmedEditedValue() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        Bundle edited = new Bundle(recorded.getBundle("arguments"));
        edited.putString("sku", "new-sku");
        fixture.host.ignoreEditedValue = true;
        AtomicReference<Exception> failure = new AtomicReference<>();
        fixture.engine.replay(recorded, edited, InAppNavigationCapture.SOURCE_READY_MS, failure::set);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertNotNull(failure.get());
        assertEquals(2, fixture.host.calls);
    }

    @Test public void waitsForFocusedHostDuringColdStartupWithoutASourceFragment() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.record(false, false, 0);
        assertNotNull(recorded);
        Bundle edited = new Bundle(recorded.getBundle("arguments"));
        edited.putString("sku", "cold-sku");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();
        fixture.host.focused = false;
        fixture.host.getFragmentManager().beginTransaction()
                .remove(fixture.host.getFragmentManager().findFragmentByTag("current"))
                .commitNow();
        fixture.engine.replay(recorded, edited, InAppNavigationCapture.COLD_SOURCE_READY_MS, failure -> {
            error.set(failure);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertFalse(completed.get());
        assertEquals(1, fixture.host.calls);

        fixture.host.focused = true;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertTrue(completed.get());
        assertNull(error.get());
        assertEquals("cold-sku", fixture.host.getFragmentManager().findFragmentByTag("current")
                .getArguments().getString("sku"));
    }

    @Test public void stillReplaysAnExistingSingleArgumentRecord() {
        Fixture fixture = new Fixture();
        String catalog = CatalogFragment.class.getName();
        Bundle original = new Bundle();
        original.putString("sku", "original-sku");
        Bundle descriptor = new Bundle();
        descriptor.putString("mechanism", "fragment_host");
        descriptor.putString("host", ShopActivity.class.getName());
        descriptor.putString("source", catalog.length() + ":" + catalog + ":1\n");
        descriptor.putString("method", "openLegacy");
        descriptor.putString("destination", ProductFragment.class.getName());
        descriptor.putBundle("arguments", original);
        descriptor.putString("fixed_bundle", "{\"aisle\":[\"Short\",\"13\"],\"region\":[\"String\",\"east\"]}");
        Bundle edited = new Bundle();
        edited.putString("sku", "new-sku");
        AtomicBoolean done = new AtomicBoolean();
        fixture.engine.replay(descriptor, edited, InAppNavigationCapture.SOURCE_READY_MS, failure -> done.set(failure == null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(done.get());
        assertEquals("new-sku", fixture.host.getFragmentManager().findFragmentByTag("current")
                .getArguments().getString("sku"));
        assertEquals((short)13, fixture.host.received.get("aisle"));
    }

    public static class CatalogFragment extends Fragment {}
    public static class ProductFragment extends Fragment {}

    public static class ShopActivity extends ShopBaseActivity {
        public void openLegacy(String sku, Bundle options) {
            openProduct(11L, options, true, sku);
        }
    }

    public static class ShopBaseActivity extends Activity {
        int calls;
        boolean ignoreEditedValue;
        boolean focused = true;
        long renderDelayMillis;
        Bundle received;

        @Override public boolean hasWindowFocus() { return focused; }

        protected int openProduct(long quantity, Bundle options, boolean transitions, String sku) {
            calls++;
            received = options;
            ProductFragment product = new ProductFragment();
            Bundle arguments = new Bundle();
            arguments.putLong("quantity", quantity);
            arguments.putBoolean("motion", transitions);
            arguments.putString("sku", ignoreEditedValue ? "original-sku" : sku);
            arguments.putString("region", options.getString("region"));
            product.setArguments(arguments);
            if (renderDelayMillis == 0) show(product);
            else new Handler(Looper.getMainLooper()).postDelayed(() -> show(product), renderDelayMillis);
            return calls;
        }

        protected void otherProduct(String sku) {}
        protected void updateCounter(long value) {}
        protected void updateNamedCounter(Bundle options) {}

        void show(Fragment fragment) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, fragment, "current").commitNow();
        }
    }

    private static final class Fixture {
        final ShopActivity host = Robolectric.buildActivity(ShopActivity.class).setup().get();
        final FragmentHostReplay engine = new FragmentHostReplay(new Handler(Looper.getMainLooper()),
                ignored -> true, () -> List.of(host), ignored -> publish());

        int trailingStringCalls;
        int repeatedProductCalls;

        Fixture() { returnToCatalog(); }

        void returnToCatalog() {
            host.show(new CatalogFragment());
            publish();
        }

        Map<String, Object> publish() {
            Fragment visible = host.getFragmentManager().findFragmentByTag("current");
            if (visible == null) {
                engine.observe(host, Map.of());
                return Map.of();
            }
            Bundle arguments = visible.getArguments();
            Map<String, Object> values = new LinkedHashMap<>();
            if (arguments != null) for (String key : arguments.keySet()) values.put(key, arguments.get(key));
            engine.observe(host, Map.of(visible.getClass().getName(), List.of(values)));
            return values;
        }

        Bundle record(boolean ambiguous, boolean unsupported, long renderDelay) throws Exception {
            String sku = "original-sku";
            Bundle extras = new Bundle();
            extras.putShort("aisle", (short)13);
            extras.putString("region", "east");
            extras.putString("unused", null);
            if (unsupported) extras.putIntArray("opaque", new int[] { 1, 2 });

            // Robolectric exercises real Android Fragment commits but mocks the LSPosed boundary.
            if (ambiguous) stageOtherProduct(sku);
            Method method = ShopBaseActivity.class.getDeclaredMethod("openProduct",
                    long.class, Bundle.class, boolean.class, String.class);
            for (int index = 0; index < repeatedProductCalls; index++) {
                end(begin(method, 11L, extras, true, sku));
            }
            Object original = begin(method, 11L, extras, true, sku);
            host.renderDelayMillis = renderDelay;
            host.openProduct(11L, extras, true, sku);
            end(original);
            for (int index = 0; index < trailingStringCalls; index++) stageOtherProduct("unrelated-" + index);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(renderDelay));
            return engine.capture(host, ProductFragment.class.getName(), publish());
        }

        void stageOtherProduct(String value) throws Exception {
            end(begin(ShopBaseActivity.class.getDeclaredMethod("otherProduct", String.class), value));
        }

        void stageCounter(long value) throws Exception {
            end(begin(ShopBaseActivity.class.getDeclaredMethod("updateCounter", long.class), value));
        }

        void stageNamedCounter(long value) throws Exception {
            Bundle options = new Bundle();
            options.putLong("quantity", value);
            end(begin(ShopBaseActivity.class.getDeclaredMethod("updateNamedCounter", Bundle.class), options));
        }

        private Object begin(Method method, Object... arguments) throws Exception {
            Method begin = FragmentHostReplay.class.getDeclaredMethod("begin", Activity.class,
                    Method.class, Object[].class);
            begin.setAccessible(true);
            Object result = begin.invoke(engine, host, method, arguments);
            assertNotNull(result);
            return result;
        }

        private void end(Object call) throws Exception {
            Method end = FragmentHostReplay.class.getDeclaredMethod("end", Activity.class,
                    call.getClass(), boolean.class);
            end.setAccessible(true);
            end.invoke(engine, host, call, true);
        }
    }
}
