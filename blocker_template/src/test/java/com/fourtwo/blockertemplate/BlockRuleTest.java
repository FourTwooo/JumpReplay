package com.fourtwo.blockertemplate;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockRuleTest {
    @Test
    public void prefixRetainsCaseAndEnabledState() {
        BlockRule rule = new BlockRule("Foo://host/private", false, true);
        assertTrue(rule.matches("Foo://host/private/item"));
        assertFalse(rule.matches("foo://host/private/item"));
        rule.setEnabled(false);
        assertFalse(rule.matches("Foo://host/private/item"));
    }

    @Test
    public void regexMatchesAndOldMalformedRulesDoNotMatch() {
        BlockRule rule = new BlockRule("[?&]ad=1(?:&|$)", true, true);
        assertTrue(rule.matches("foo://host/page?ad=1&source=home"));
        assertFalse(rule.matches("foo://host/page?ad=10"));
        rule.setPattern("[");
        assertFalse(rule.matches("foo://host/page"));
    }
}
