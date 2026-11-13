package com.fourtwo.hookintent.manager;

import android.app.Application;
import android.os.Looper;
import android.os.Bundle;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.viewmodel.MainViewModel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=33)
public class CaptureControlTest {
    @Test public void recreatingViewModelsDoesNotResetGlobalCapture() {
        HookStatusManager.initialize(RuntimeEnvironment.getApplication());
        HookStatusManager.setHook(true);
        assertEquals(Boolean.TRUE,new MainViewModel().getIsHook().getValue());
        new MainViewModel().setIsHook(false);
        assertFalse(HookStatusManager.isHook());
        assertEquals(Boolean.FALSE,new MainViewModel().getIsHook().getValue());
    }
    @Test public void oldSessionCallbacksCannotContaminateNewSessionAndCommittedBatchesCountRecords() {
        CaptureMonitor.initialize(RuntimeEnvironment.getApplication()); CaptureMonitor.reset();
        ItemData item=new ItemData(null,"Source app","","","","",new Bundle(),"Intent","","");
        CaptureMonitor.committed(Collections.singletonList(item));
        CaptureMonitor.reset();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,CaptureMonitor.changes().getValue().count);
        CaptureMonitor.committed(Arrays.asList(item,item)); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2,CaptureMonitor.changes().getValue().count);
        assertEquals("Source app",CaptureMonitor.changes().getValue().source);
        assertTrue(CaptureMonitor.changes().getValue().time > 0);
    }
}
