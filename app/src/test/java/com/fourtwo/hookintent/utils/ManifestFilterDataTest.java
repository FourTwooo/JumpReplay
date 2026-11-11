package com.fourtwo.hookintent.utils;

import com.fourtwo.hookintent.ui.blocker.DeepLinkRule;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ManifestFilterDataTest {
    @Test
    public void dataTagsWithOnlyHostOrPathStillConstrainTheFilter() {
        ManifestFilterData data = new ManifestFilterData();
        data.add("Foo", "", "", "", "", "");
        data.add("", "a.example", "443", "", "", "");
        data.add("", "", "", "", "/private", "");
        List<DeepLinkRule> rules = data.toRules("app", "App", "app.Viewer");
        assertEquals(1, rules.size());
        assertEquals("Foo", rules.get(0).getScheme());
        assertEquals("a.example", rules.get(0).getHost());
        assertEquals("443", rules.get(0).getPort());
        assertEquals("/private", rules.get(0).getPathPrefix());
    }

    @Test
    public void schemesAuthoritiesAndPathsFormIndependentAlternatives() {
        ManifestFilterData data = new ManifestFilterData();
        data.add("foo", "a.example", "", "/first", "", "");
        data.add("bar", "b.example", "8443", "", "/second", "");
        List<DeepLinkRule> rules = data.toRules("app", "App", "app.Viewer");
        assertEquals(8, rules.size());
        assertTrue(rules.stream().anyMatch(r -> "foo".equals(r.getScheme())
                && "b.example".equals(r.getHost()) && "8443".equals(r.getPort())
                && "/second".equals(r.getPathPrefix())));
    }

    @Test
    public void pathWithoutHostIsIgnoredAndRepeatedDataIsDeduplicated() {
        ManifestFilterData data = new ManifestFilterData();
        data.add("foo", "", "", "/ignored", "", "");
        data.add("foo", "", "", "/ignored", "", "");
        List<DeepLinkRule> rules = data.toRules("app", "App", "app.Viewer");
        assertEquals(1, rules.size());
        assertEquals("", rules.get(0).getPath());
    }

    @Test
    public void portWithoutHostDoesNotConstrainAnotherDataElement() {
        ManifestFilterData data = new ManifestFilterData();
        data.add("foo", "", "8443", "", "", "");
        data.add("", "a.example", "", "", "", "");
        List<DeepLinkRule> rules = data.toRules("app", "App", "app.Viewer");
        assertEquals(1, rules.size());
        assertEquals("a.example", rules.get(0).getHost());
        assertEquals("", rules.get(0).getPort());
    }

    @Test
    public void pathMatcherTypesAreAlternativesRatherThanCombinedConstraints() {
        ManifestFilterData data = new ManifestFilterData();
        data.add("foo", "a.example", "443", "/exact", "/prefix", "/pattern/.*");
        List<DeepLinkRule> rules = data.toRules("app", "App", "app.Viewer");
        assertEquals(3, rules.size());
        assertTrue(rules.stream().anyMatch(r -> "/exact".equals(r.getPath())
                && r.getPathPrefix().isEmpty() && r.getPathPattern().isEmpty()));
        assertTrue(rules.stream().anyMatch(r -> "/prefix".equals(r.getPathPrefix())
                && r.getPath().isEmpty() && r.getPathPattern().isEmpty()));
        assertTrue(rules.stream().anyMatch(r -> "/pattern/.*".equals(r.getPathPattern())
                && r.getPath().isEmpty() && r.getPathPrefix().isEmpty()));
    }
}
