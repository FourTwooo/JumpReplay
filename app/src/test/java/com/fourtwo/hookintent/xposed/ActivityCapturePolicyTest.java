package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Intent;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ActivityCapturePolicyTest {
    private StackTraceElement frame(String owner, String method) { return new StackTraceElement(owner, method, "", 0); }
    @Test public void oldSenderSwitchesAreHonoredAcrossDelegatedChain() {
        StackTraceElement[] stack = {frame("android.app.Instrumentation", "execStartActivity"),
                frame("android.app.Activity", "startActivityForResult"), frame("android.app.Activity", "startActivity")};
        assertFalse(ActivityCapturePolicy.senderAllowed(stack, (c,m) -> false));
        assertTrue(ActivityCapturePolicy.senderAllowed(stack, (c,m) -> m.equals("startActivity")));
        assertTrue(ActivityCapturePolicy.senderAllowed(stack, (c,m) -> m.equals("startActivityForResult")));
    }
    @Test public void independentAppTaskAndBatchedPathsDoNotDependOnLegacyWrapperSwitches() {
        assertTrue(ActivityCapturePolicy.senderAllowed(new StackTraceElement[]{
                frame("android.app.Instrumentation", "execStartActivitiesAsUser")}, (c,m) -> false));
    }
    @Test public void batchOrderNullsAndRepeatedIntentsArePreservedWithoutUnpackingExtras() {
        Intent a = new Intent("a").putExtra("int", 17), b = new Intent("b");
        assertEquals(Arrays.asList(a, b, a), ActivityCapturePolicy.sendingIntents(new Object[]{
                null, "ignored", new Intent[]{a, null, b, a}, new android.os.Bundle()}));
        assertEquals(17, a.getIntExtra("int", 0));
    }
}
