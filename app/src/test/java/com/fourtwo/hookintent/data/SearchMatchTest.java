package com.fourtwo.hookintent.data;

import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchMatchTest {
    @Test public void regexEscapesRetainTheirMeaning() {
        Pattern pattern = Pattern.compile("entry\\D+");
        assertTrue(SearchMatch.find(pattern, "entryXYZ", System.nanoTime() + 1000000000L));
        assertFalse(SearchMatch.find(pattern, "entry123", System.nanoTime() + 1000000000L));
    }
    @Test(expected = SearchMatch.TooSlow.class) public void expiredSearchStopsBeforeMatching() {
        SearchMatch.find(Pattern.compile("(a+)+$"), "aaaa!", System.nanoTime() - 1);
    }
}
