package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import de.robv.android.xposed.XC_MethodHook;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {24, 28, 30, 33})
public class ActivityCaptureHooksTest {
    public static class NoSuperActivity extends Activity {
        @Override protected void onNewIntent(Intent intent) { }
    }
    private final Map<Method, XC_MethodHook> hooks = new LinkedHashMap<>();
    private final List<Intent> captured = new ArrayList<>();
    private boolean enabled = true;
    private ActivityCaptureHooks engine;
    @Before public void setup() {
        RecordXposedBridge.clearHookedRecords(); RecordXposedBridge.isHostApp = false;
        engine = new ActivityCaptureHooks(() -> enabled,
                (intent, function, from) -> captured.add(new Intent(intent)), hooks::put);
    }
    private void install() { engine.install(getClass().getClassLoader()); }
    private Method method(String name) {
        return hooks.keySet().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow(AssertionError::new);
    }
    private XC_MethodHook.MethodHookParam before(Method target, Object receiver, Object... args) throws Exception {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = target; param.thisObject = receiver; param.args = args;
        Method callback = XC_MethodHook.class.getDeclaredMethod("beforeHookedMethod", XC_MethodHook.MethodHookParam.class);
        callback.setAccessible(true); callback.invoke(hooks.get(target), param);
        return param;
    }
    private void after(Method target, XC_MethodHook.MethodHookParam param) throws Exception {
        Method callback = XC_MethodHook.class.getDeclaredMethod("afterHookedMethod", XC_MethodHook.MethodHookParam.class);
        callback.setAccessible(true); callback.invoke(hooks.get(target), param);
    }
    private void disable(String owner, String method) {
        Map<String,Object> r = new LinkedHashMap<>(); r.put("category", "Intent");
        r.put("className", owner); r.put("packageName", "ALL"); r.put("methodName", method); r.put("open", false);
        RecordXposedBridge.setHookedRecords(new ArrayList<>(Arrays.asList(r)));
    }
    @Test public void onlyIndependentLaunchAndDeliveryMethodsAreInstalled() {
        install();
        assertFalse(hooks.isEmpty());
        assertTrue(hooks.keySet().stream().noneMatch(m -> Arrays.asList("startActivity", "startActivityForResult",
                "onResume", "onNewIntent", "execStartActivities").contains(m.getName())));
        assertEquals(Build.VERSION.SDK_INT < 29 ? 2 : 1,
                hooks.keySet().stream().filter(m -> m.getName().equals("performCreate")).count());
        assertEquals(1, hooks.keySet().stream().filter(m -> m.getName().equals("deliverNewIntents")).count());
        assertEquals(1, hooks.keySet().stream().filter(m -> m.getName().equals("startActivityAsUser")).count());
        assertNotNull(method("execStartActivity")); assertNotNull(method("execStartActivitiesAsUser"));
        assertNotNull(method("execStartActivityAsCaller")); assertNotNull(method("execStartActivityFromAppTask"));
        assertEquals(1, hooks.keySet().stream().filter(m -> m.getName().equals("startActivityIfNeeded")).count());
        assertEquals(1, hooks.keySet().stream().filter(m -> m.getName().equals("startNextMatchingActivity")).count());
    }
    @Test public void batchCapturesEveryIntentAndSeparateIdenticalLaunchesAreNotSuppressed() throws Exception {
        install(); Method target = method("execStartActivitiesAsUser");
        Intent a = new Intent("a").putExtra("key", 4), b = new Intent("b");
        before(target, null, (Object) new Intent[]{a, b, a});
        before(target, null, (Object) new Intent[]{a});
        assertEquals(4, captured.size());
        assertEquals(Arrays.asList("a", "b", "a", "a"), Arrays.asList(captured.get(0).getAction(), captured.get(1).getAction(), captured.get(2).getAction(), captured.get(3).getAction()));
        assertEquals(4, captured.get(0).getIntExtra("key", 0));
    }
    @Test public void realDeliveredIntentDoesNotDependOnGetIntentOrCallingSuper() throws Exception {
        install(); Method target = method("deliverNewIntents");
        Class<?> recordType = target.getParameterTypes()[0];
        Object record = ReflectionHelpers.callConstructor(recordType);
        Activity receiver = Robolectric.buildActivity(NoSuperActivity.class).create().get();
        receiver.setIntent(new Intent("old"));
        Field field = recordType.getDeclaredField("activity"); field.setAccessible(true); field.set(record, receiver);
        before(target, null, record, Arrays.asList(new Intent("new1").putExtra("key", 6), new Intent("new2")));
        assertEquals(2, captured.size()); assertEquals("new1", captured.get(0).getAction());
        assertEquals(6, captured.get(0).getIntExtra("key", 0)); assertEquals("new2", captured.get(1).getAction());
        assertEquals("old", receiver.getIntent().getAction());
    }
    @Test public void createCapturesOnlyAtEntryAndGlobalSwitchStopsCaptures() throws Exception {
        install(); Method target = method("performCreate");
        Activity receiver = new Activity(); receiver.setIntent(new Intent("launch"));
        XC_MethodHook.MethodHookParam first = before(target, receiver, null, null); after(target, first);
        enabled = false;
        XC_MethodHook.MethodHookParam second = before(target, receiver, null, null); after(target, second);
        assertEquals(1, captured.size()); assertEquals("launch", captured.get(0).getAction());
    }
    @Test public void oldDisabledReceiverSwitchIsPreserved() {
        disable("android.app.Activity", "onNewIntent"); install();
        assertTrue(hooks.keySet().stream().noneMatch(m -> m.getName().equals("deliverNewIntents")));
    }
    @Test public void directBinderActivityPathsCaptureTheirActualIntent() throws Exception {
        install();
        before(method("startActivityIfNeeded"), new Activity(), new Intent("needed"), 0, null);
        before(method("startNextMatchingActivity"), new Activity(), new Intent("next"), null);
        assertEquals(2, captured.size()); assertEquals("needed", captured.get(0).getAction());
        assertEquals("next", captured.get(1).getAction());
    }
    @Test public void delegatedCreateOverloadsOnlyCaptureOnce() throws Exception {
        install();
        List<Method> create = new ArrayList<>();
        for (Method m : hooks.keySet()) if (m.getName().equals("performCreate")) create.add(m);
        org.junit.Assume.assumeTrue(create.size() == 2);
        Activity receiver = new Activity(); receiver.setIntent(new Intent("launch"));
        XC_MethodHook.MethodHookParam first = before(create.get(0), receiver, (Object) null);
        XC_MethodHook.MethodHookParam nested = before(create.get(1), receiver, null, null);
        after(create.get(1), nested); after(create.get(0), first);
        assertEquals(1, captured.size());
        XC_MethodHook.MethodHookParam next = before(create.get(0), receiver, (Object) null);
        after(create.get(0), next);
        assertEquals(2, captured.size());
    }
    @Test public void disabledPhysicalSenderSwitchIsPreserved() {
        disable("android.app.Instrumentation", "execStartActivity"); install();
        assertTrue(hooks.keySet().stream().noneMatch(m -> m.getName().equals("execStartActivity")));
        assertNotNull(method("execStartActivitiesAsUser"));
    }
    @Test public void disablingAllOldSendersDoesNotEnableNewPathsOnUpgrade() {
        List<Map<String,Object>> records = new ArrayList<>();
        for (String[] old : new String[][]{{"android.app.Activity", "startActivity"},
                {"android.app.Activity", "startActivityForResult"}, {"android.content.ContextWrapper", "startActivity"}}) {
            Map<String,Object> record = new LinkedHashMap<>(); record.put("category", "Intent");
            record.put("className", old[0]); record.put("methodName", old[1]);
            record.put("packageName", "ALL"); record.put("open", false); records.add(record);
        }
        RecordXposedBridge.setHookedRecords(records); install();
        assertTrue(hooks.keySet().stream().noneMatch(m -> m.getName().startsWith("execStart")
                || Arrays.asList("startActivityAsUser", "startActivityIfNeeded", "startNextMatchingActivity").contains(m.getName())));
    }
    @Test public void hostAppRecordsConfigurationWithoutInstallingAnyCaptureHooks() {
        RecordXposedBridge.isHostApp = true; install();
        assertTrue(hooks.isEmpty());
        assertTrue(RecordXposedBridge.getHookedRecords().stream().anyMatch(r -> "execStartActivity".equals(r.get("methodName"))));
    }
}
