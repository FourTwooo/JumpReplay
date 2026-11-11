package com.fourtwo.hookintent.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShortcutParameterReplacerTest {
    @Test public void queryValueCannotCreateAnotherParameter() {
        assertEquals("demo://host?q=a%2Bb%26admin%3Dtrue",
                ShortcutParameterReplacer.replace("demo://host?q=VALUE", new String[]{"VALUE"},
                        new String[]{"a+b&admin=true"}));
    }

    @Test public void intentStringExtraCannotCreateAnotherField() {
        assertEquals("intent:#Intent;S.name=a%3BS.admin%3Dtrue;end",
                ShortcutParameterReplacer.replace("intent:#Intent;S.name=VALUE;end", new String[]{"VALUE"},
                        new String[]{"a;S.admin=true"}));
    }

    @Test public void replacementsAreSimultaneousAndLongestMatchWins() {
        assertEquals("BB/a", ShortcutParameterReplacer.replace("AA/A", new String[]{"A", "AA"},
                new String[]{"a", "BB"}));
        assertEquals("B/new", ShortcutParameterReplacer.replace("A/B", new String[]{"A", "B"},
                new String[]{"B", "new"}));
    }

    @Test public void fullUriPlaceholderStillAllowsWholeUri() {
        assertEquals("https://example.org/path?q=1", ShortcutParameterReplacer.replace("URL",
                new String[]{"URL"}, new String[]{"https://example.org/path?q=1"}));
    }

    @Test public void pathValueCannotCreateIntentOrQuerySyntax() {
        assertEquals("demo://host/a%2Fb%3Fq%3D1%23Intent%3Bend",
                ShortcutParameterReplacer.replace("demo://host/VALUE", new String[]{"VALUE"},
                        new String[]{"a/b?q=1#Intent;end"}));
    }

    @Test(expected = IllegalArgumentException.class) public void emptyPlaceholderIsRejected() {
        ShortcutParameterReplacer.replace("abc", new String[]{""}, new String[]{"x"});
    }

    @Test(expected = IllegalArgumentException.class) public void duplicatePlaceholderIsRejected() {
        ShortcutParameterReplacer.validate("abc", new String[]{"a", "a"});
    }
}
