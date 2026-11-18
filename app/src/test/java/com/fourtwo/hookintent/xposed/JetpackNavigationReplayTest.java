package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.navigation.NavAction;
import androidx.navigation.NavArgument;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavGraph;
import androidx.navigation.NavGraphNavigator;
import androidx.navigation.NavOptions;
import androidx.navigation.NavType;
import androidx.navigation.Navigator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class JetpackNavigationReplayTest {
    private static final int SOURCE = 1;
    private static final int DESTINATION = 2;
    private static final int OTHER_DESTINATION = 3;
    private static final int ACTION = 4;
    private static final int GRAPH = 5;

    @Test public void replaysActualActionWithEditedArgumentsAndConfirmsDestination() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.recordActualNavigation();
        Bundle edited = arguments("changed");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Exception> error = new AtomicReference<>();

        fixture.engine.replay(recorded, edited, failure -> {
            error.set(failure);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(completed.get());
        assertNull(error.get());
        assertEquals(DESTINATION, fixture.controller.getCurrentDestination().getId());
        assertEquals("changed", fixture.controller.getCurrentBackStackEntry().getArguments().getString("value"));
    }

    @Test public void rejectsChangedActionBeforeDispatchingToUnexpectedPage() throws Exception {
        Fixture fixture = new Fixture();
        Bundle recorded = fixture.recordActualNavigation();
        fixture.source.putAction(ACTION, new NavAction(OTHER_DESTINATION));
        AtomicReference<Exception> error = new AtomicReference<>();

        fixture.engine.replay(recorded, arguments("changed"), error::set);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(error.get());
        assertEquals(SOURCE, fixture.controller.getCurrentDestination().getId());
    }

    @Test public void stringRouteEditsStayInTheCapturedDestination() throws Exception {
        Fixture fixture = new Fixture(true);
        Bundle recorded = fixture.recordActualNavigation();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        fixture.engine.replay(recorded, arguments("collision"), error -> {
            failure.set(error);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(completed.get());
        assertNotNull(failure.get());
        assertEquals("home", fixture.controller.getCurrentDestination().getRoute());

        completed.set(false);
        fixture.engine.replay(recorded, arguments("two/parts"), error -> {
            failure.set(error);
            completed.set(true);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(completed.get());
        assertNull(failure.get());
        assertEquals("detail/{value}", fixture.controller.getCurrentDestination().getRoute());
        assertEquals("two/parts", fixture.controller.getCurrentBackStackEntry().getArguments().getString("value"));
    }

    private static Bundle arguments(String value) {
        Bundle result = new Bundle();
        result.putString("value", value);
        return result;
    }

    public static class FocusedActivity extends Activity {
        @Override public boolean hasWindowFocus() { return true; }
    }

    @Navigator.Name("ReplayTest")
    public static class TestNavigator extends Navigator<NavDestination> {
        @Override public NavDestination createDestination() { return new NavDestination(this); }

        @Override public NavDestination navigate(NavDestination destination, Bundle arguments,
                NavOptions options, Navigator.Extras extras) {
            return destination;
        }

        @Override public boolean popBackStack() { return true; }
    }

    private static final class Fixture {
        final NavController controller;
        final NavDestination source;
        final JetpackNavigationReplay engine;
        final boolean byRoute;

        Fixture() { this(false); }

        Fixture(boolean byRoute) {
            this.byRoute = byRoute;
            FocusedActivity owner = Robolectric.buildActivity(FocusedActivity.class).setup().get();
            controller = new NavController(owner);
            TestNavigator navigator = new TestNavigator();
            controller.getNavigatorProvider().addNavigator(navigator);
            NavGraph graph = new NavGraph(new NavGraphNavigator(controller.getNavigatorProvider()));
            graph.setId(GRAPH);
            source = destination(navigator, SOURCE);
            NavDestination detail = destination(navigator, DESTINATION);
            NavDestination other = destination(navigator, OTHER_DESTINATION);
            if (byRoute) {
                source.setRoute("home");
                detail.setRoute("detail/{value}");
                detail.addArgument("value", new NavArgument.Builder().setType(NavType.StringType).build());
                other.setRoute("detail/collision");
            } else {
                source.putAction(ACTION, new NavAction(DESTINATION));
            }
            graph.addDestination(source);
            graph.addDestination(detail);
            graph.addDestination(other);
            graph.setStartDestination(source.getId());
            controller.setGraph(graph);
            engine = new JetpackNavigationReplay(new Handler(Looper.getMainLooper()), () -> true,
                    ignored -> true);
            engine.register(controller, owner);
            controller.addOnDestinationChangedListener((changed, destination, arguments) ->
                    engine.observe(changed, destination, arguments, null));
        }

        Bundle recordActualNavigation() throws Exception {
            // The test calls both sides of the Xposed boundary with real Jetpack objects.
            Field controllers = JetpackNavigationReplay.class.getDeclaredField("controllers");
            controllers.setAccessible(true);
            Object state = ((Map<?, ?>) controllers.get(engine)).get(controller);
            Method begin = JetpackNavigationReplay.class.getDeclaredMethod("begin", Object.class,
                    state.getClass(), Object[].class, boolean.class);
            begin.setAccessible(true);
            Bundle arguments = arguments("initial");
            Object[] observed = byRoute ? new Object[]{"detail/initial", null, null}
                    : new Object[]{ACTION, arguments, null, null};
            Object invocation = begin.invoke(engine, controller, state, observed, !byRoute);
            assertNotNull(invocation);

            if (byRoute) controller.navigate("detail/initial");
            else controller.navigate(ACTION, arguments);
            Method describe = JetpackNavigationReplay.class.getDeclaredMethod("descriptorForResult",
                    Object.class, invocation.getClass(), Object.class, Bundle.class);
            describe.setAccessible(true);
            Bundle record = (Bundle) describe.invoke(engine, controller, invocation,
                    controller.getCurrentDestination(), controller.getCurrentBackStackEntry().getArguments());
            assertNotNull(record);
            assertTrue(controller.popBackStack());
            return record;
        }

        private static NavDestination destination(TestNavigator navigator, int identifier) {
            NavDestination result = navigator.createDestination();
            result.setId(identifier);
            return result;
        }
    }
}
