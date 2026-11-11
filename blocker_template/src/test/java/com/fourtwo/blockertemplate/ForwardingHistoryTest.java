package com.fourtwo.blockertemplate;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ForwardingHistoryTest {
    @Test
    public void anotherBlockerRemainsAvailableUntilVisited() {
        List<String> visited = ForwardingHistory.withVisit(null, "blocker.a");
        assertTrue(ForwardingHistory.shouldExclude(true, "blocker.a", visited));
        assertFalse(ForwardingHistory.shouldExclude(true, "blocker.b", visited));
        visited = ForwardingHistory.withVisit(visited, "blocker.b");
        assertTrue(ForwardingHistory.shouldExclude(true, "blocker.a", visited));
        assertTrue(ForwardingHistory.shouldExclude(true, "blocker.b", visited));
        assertFalse(ForwardingHistory.shouldExclude(false, "real.target", visited));
    }

    @Test
    public void revisitsDoNotGrowHistoryAndNormalHandlersAreNotFiltered() {
        List<String> visited = ForwardingHistory.withVisit(
                Arrays.asList("blocker.a", null, "", "blocker.a", "real.target"), "blocker.a");
        assertEquals(Arrays.asList("blocker.a", "real.target"), visited);
        assertFalse(ForwardingHistory.shouldExclude(false, "real.target", visited));
    }
}
