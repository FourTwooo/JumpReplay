package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class SchemeResolverTest {
    private ActivityInfo activity(String pkg) {
        ActivityInfo a = new ActivityInfo(); a.packageName = pkg; a.name = pkg + ".Screen";
        a.enabled = true; a.exported = true;
        a.applicationInfo = new ApplicationInfo(); a.applicationInfo.packageName = pkg;
        a.applicationInfo.enabled = true; a.applicationInfo.uid = 10001;
        return a;
    }
    private ResolveInfo target(String pkg, int priority) {
        ResolveInfo r = new ResolveInfo(); r.activityInfo = activity(pkg);
        r.priority = priority; r.isDefault = true; r.match = 0x208000; return r;
    }
    private Intent intent() { return new Intent(Intent.ACTION_VIEW, Uri.parse("example://chat/123")); }

    @Test public void candidateTiesHighestPriorityBeforeLowerPriorityCandidate() {
        ResolveInfo high = target("high", 100), low = target("low", 0);
        List<ResolveInfo> original = Collections.unmodifiableList(Arrays.asList(high, low));
        ActivityInfo host = activity("host");
        List<ResolveInfo> result = SchemeResolver.inject(original, intent(), host);
        assertEquals(2, original.size()); assertEquals(3, result.size());
        assertSame(high, result.get(0)); assertSame(low, result.get(2));
        assertEquals(100, result.get(1).priority); assertEquals(high.isDefault, result.get(1).isDefault);
        assertNotSame(host, result.get(1).activityInfo);
        assertNotSame(host.applicationInfo, result.get(1).activityInfo.applicationInfo);
    }
    @Test public void emptyResultsCanResolveToInterceptorWithMatchingFilter() {
        Intent source = intent().addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo result = SchemeResolver.inject(Collections.emptyList(), source, activity("host")).get(0);
        assertTrue(result.filter.match(source.getAction(), source.getType(), source.getScheme(),
                source.getData(), source.getCategories(), "test") >= 0);
        assertTrue(result.isDefault); assertTrue(result.match >= IntentFilter.MATCH_CATEGORY_SCHEME);
    }
    @Test public void injectionIsIdempotentAndExistingCandidateIsPreserved() {
        List<ResolveInfo> result = SchemeResolver.inject(Collections.singletonList(target("other", 0)), intent(), activity("host"));
        assertNull(SchemeResolver.inject(result, intent(), activity("host")));
        assertEquals(2, result.size());
    }
    @Test public void malformedOemResultsAndDisabledOrNonExportedActivitiesKeepOriginalResult() {
        assertNull(SchemeResolver.inject(Arrays.asList("unknown"), intent(), activity("host")));
        assertNull(SchemeResolver.inject(Collections.singletonList(new ResolveInfo()), intent(), activity("host")));
        ActivityInfo disabled = activity("host"); disabled.enabled = false;
        assertNull(SchemeResolver.inject(Collections.emptyList(), intent(), disabled));
        ActivityInfo privateActivity = activity("host"); privateActivity.exported = false;
        assertNull(SchemeResolver.inject(Collections.emptyList(), intent(), privateActivity));
    }
    @Test public void defaultStatusIsNotInventedAgainstOriginalHighestResult() {
        ResolveInfo original = target("other", 0); original.isDefault = false;
        List<ResolveInfo> result = SchemeResolver.inject(Collections.singletonList(original), intent(), activity("host"));
        assertFalse(result.get(1).isDefault);
    }
    @Test public void disabledRuleStillAppliesWhenHostAlreadyResolvesHttp() {
        List<ResolveInfo> original = Collections.singletonList(target("host", 1000));
        Object result = SchemeResolver.resolve(original, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")),
                0, activity("host"), (scheme, uri) -> uri.contains("example.com"));
        assertTrue(result instanceof List); assertTrue(((List<?>) result).isEmpty());
        assertEquals(1, original.size());
    }
    @Test public void missingCacheUnknownResultAndOtherUserLeaveResultUnmodified() {
        List<ResolveInfo> original = Collections.singletonList(target("other", 0));
        assertSame(original, SchemeResolver.resolve(original, intent(), 0, null, (s,u) -> true));
        assertSame(original, SchemeResolver.resolve(original, intent(), 10, activity("host"), (s,u) -> true));
        Object unknown = new Object();
        assertSame(unknown, SchemeResolver.resolve(unknown, intent(), 0, activity("host"), (s,u) -> true));
        assertSame(original, SchemeResolver.resolve(original, intent().setPackage("other"), 0, activity("host"), (s,u) -> true));
    }
}
