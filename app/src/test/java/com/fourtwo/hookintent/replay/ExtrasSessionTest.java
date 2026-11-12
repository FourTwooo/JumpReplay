package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ExtrasSessionTest {
    private ExtrasSession session(Intent target) { return new ExtrasSession(IntentPayloadCodec.encode(target), getClass().getClassLoader()); }
    private JSONObject node(JSONArray list, String name) throws Exception {
        for (int i = 0; i < list.length(); i++) if (name.equals(list.getJSONObject(i).getString("name"))) return list.getJSONObject(i);
        throw new AssertionError("Node not found: " + name);
    }
    private Intent decode(ExtrasSession session) {
        Bundle record = new Bundle(); record.putString(IntentPayloadCodec.KEY, session.parcel());
        Intent result = IntentPayloadCodec.decode(record); result.setExtrasClassLoader(getClass().getClassLoader()); return result;
    }

    @Test public void editsPrivateInheritedParcelableFieldsAndLeavesOriginalUntouched() throws Exception {
        PostData data = new PostData("initial", 7, 123L);
        Intent target = new Intent("test.action").putExtra("payload", data).putExtra("normal", 1);
        ExtrasSession session = session(target);
        String payload = node(session.children("root"), "payload").getString("id");
        JSONArray children = session.children(payload);
        session.set(node(children, "title").getString("id"), "changed", false);
        session.set(node(children, "quantity").getString("id"), "11", false);
        session.set(node(children, "inherited").getString("id"), "999", false);
        PostData restored = decode(session).getParcelableExtra("payload");
        assertEquals("changed", restored.title); assertEquals(11, restored.quantity); assertEquals(999, restored.inherited);
        assertEquals("initial", data.title); assertEquals(7, data.quantity); assertEquals(123L, data.inherited);
        assertEquals(1, decode(session).getIntExtra("normal", 0));
    }

    @Test public void serializableCyclesAndSharedReferencesSurviveNestedEditing() throws Exception {
        Graph graph = new Graph(); graph.name = "old"; graph.self = graph;
        graph.shared = new Graph(); graph.shared.name = "shared"; graph.alias = graph.shared;
        ExtrasSession session = session(new Intent().putExtra("graph", graph));
        String id = node(session.children("root"), "graph").getString("id");
        JSONArray children = session.children(id);
        assertEquals(id, node(children, "self").getString("reference"));
        String shared = node(children, "shared").getString("id");
        session.set(node(session.children(shared), "name").getString("id"), "edited", false);
        Graph restored = (Graph) decode(session).getSerializableExtra("graph");
        assertSame(restored, restored.self); assertSame(restored.shared, restored.alias);
        assertEquals("edited", restored.alias.name); assertEquals("shared", graph.shared.name);
    }

    @Test public void nestedArraysListsMapsAndBundleKeepTheirTypes() throws Exception {
        Bundle nested = new Bundle(); nested.putByteArray("bytes", new byte[]{1, 2});
        ArrayList<String> list = new ArrayList<>(); list.add("first");
        Map<String, Object> map = new LinkedHashMap<>(); map.put("entry", list);
        Intent target = new Intent().putExtra("nested", nested).putExtra("map", (Serializable)map);
        ExtrasSession session = session(target);
        String bundle = node(session.children("root"), "nested").getString("id");
        String bytes = node(session.children(bundle), "bytes").getString("id");
        session.set(node(session.children(bytes), "[1]").getString("id"), "127", false);
        String mapId = node(session.children("root"), "map").getString("id");
        String listId = node(session.children(mapId), "entry").getString("id");
        session.set(node(session.children(listId), "[0]").getString("id"), "last", false);
        assertArrayEquals(new byte[]{1, 127}, decode(session).getBundleExtra("nested").getByteArray("bytes"));
        assertEquals("last", ((ArrayList<?>)((Map<?, ?>)decode(session).getSerializableExtra("map")).get("entry")).get(0));
    }

    @Test public void numericOverflowAndInvalidBooleanAreAtomicAndNullNeedsExplicitType() throws Exception {
        Intent target = new Intent().putExtra("byte", (byte)7).putExtra("boolean", false).putExtra("null", (String)null);
        ExtrasSession session = session(target);
        JSONArray nodes = session.children("root");
        String before = session.parcel();
        try { session.set(node(nodes, "byte").getString("id"), "128", false); fail(); } catch (IllegalArgumentException expected) { }
        try { session.set(node(nodes, "boolean").getString("id"), "yes", false); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(before, session.parcel());
        JSONObject nullable = node(nodes, "null"); assertTrue(nullable.getBoolean("isNull")); assertTrue(nullable.getBoolean("untyped"));
        try { session.set(nullable.getString("id"), "9", false); fail(); } catch (IllegalArgumentException expected) { }
        session.setTyped(nullable.getString("id"), "java.lang.Long", "9", false);
        assertEquals(9L, decode(session).getLongExtra("null", -1));
    }

    @Test public void ignoredCustomFieldModificationIsRejectedAndRolledBack() throws Exception {
        ExtrasSession session = session(new Intent().putExtra("payload", new Lossy()));
        String payload = node(session.children("root"), "payload").getString("id");
        JSONObject field = node(session.children(payload), "ignored");
        String before = session.parcel();
        try { session.set(field.getString("id"), "changed", false); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("撤销")); }
        assertEquals(before, session.parcel());
        assertEquals("fixed", ((Lossy)decode(session).getParcelableExtra("payload")).ignored);
    }

    @Test public void setsCanReplaceScalarsAndEditObjectsWithoutLosingContainerType() throws Exception {
        java.util.Set<String> names = new java.util.HashSet<>(); names.add("old"); names.add("keep");
        Graph graph = new Graph(); graph.name = "nested";
        java.util.Set<Graph> objects = new java.util.LinkedHashSet<>(); objects.add(graph);
        ExtrasSession session = session(new Intent().putExtra("names", (Serializable)names).putExtra("objects", (Serializable)objects));
        String namesId = node(session.children("root"), "names").getString("id");
        JSONArray elements = session.children(namesId);
        JSONObject old = elements.getJSONObject(0).getString("value").equals("old") ? elements.getJSONObject(0) : elements.getJSONObject(1);
        session.set(old.getString("id"), "new", false);
        String objectsId = node(session.children("root"), "objects").getString("id");
        String objectId = session.children(objectsId).getJSONObject(0).getString("id");
        session.set(node(session.children(objectId), "name").getString("id"), "edited", false);
        assertEquals(java.util.HashSet.class, decode(session).getSerializableExtra("names").getClass());
        assertTrue(((java.util.Set<?>) decode(session).getSerializableExtra("names")).contains("new"));
        java.util.Set<?> restored = (java.util.Set<?>) decode(session).getSerializableExtra("objects");
        assertEquals(java.util.LinkedHashSet.class, restored.getClass());
        assertEquals("edited", ((Graph) restored.iterator().next()).name);
    }

    @Test public void parcelableTransientFieldsAndSparseArrayValuesRemainEditable() throws Exception {
        SparseHolder original = new SparseHolder(); original.explicitTransient = 4; original.values.put(42, "old");
        ExtrasSession session = session(new Intent().putExtra("holder", original));
        String holder = node(session.children("root"), "holder").getString("id");
        session.set(node(session.children(holder), "explicitTransient").getString("id"), "8", false);
        String values = node(session.children(holder), "values").getString("id");
        session.set(node(session.children(values), "[42]").getString("id"), "changed", false);
        SparseHolder restored = decode(session).getParcelableExtra("holder");
        assertEquals(8, restored.explicitTransient); assertEquals("changed", restored.values.get(42));
    }

    @Test public void uriMergePreservesCustomExtrasAndRollsBackInvalidSyntax() throws Exception {
        Intent target = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("demo://host/old"));
        target.putExtra("payload", new PostData("custom", 4, 8)); target.putExtra("normal", "old");
        String originalText = target.toUri(Intent.URI_INTENT_SCHEME);
        ExtrasSession session = session(target);
        session.merge(originalText.replace("S.normal=old", "S.normal=new"), originalText);
        assertEquals("new", decode(session).getStringExtra("normal"));
        assertEquals("custom", ((PostData)decode(session).getParcelableExtra("payload")).title);
        String before = session.parcel();
        try { session.merge("", originalText); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(before, session.parcel());
        assertTrue(session.children("root").length() > 0);
    }

    @Test public void mapKeysCanBeRenamedAndKeyConflictsRollBackAtomically() throws Exception {
        Map<String, String> map = new LinkedHashMap<>(); map.put("first", "one"); map.put("second", "two");
        ExtrasSession session = session(new Intent().putExtra("map", (Serializable)map));
        String mapId = node(session.children("root"), "map").getString("id");
        JSONArray entries = session.children(mapId);
        String valueId = node(entries, "first").getString("id");
        String keyId = node(entries, "[key] first").getString("id");
        session.set(keyId, "renamed", false);
        // A sibling value ID remains usable after its key's fingerprint changes.
        session.set(valueId, "updated", false);
        Map<?, ?> result = (Map<?, ?>)decode(session).getSerializableExtra("map");
        assertEquals("updated", result.get("renamed")); assertFalse(result.containsKey("first"));
        String before = session.parcel();
        try { session.set(keyId, "second", false); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(before, session.parcel());
    }

    @Test public void nonListCollectionDuplicateElementsHaveDistinctPositionsAndDateRetainsClass() throws Exception {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(); queue.add("same"); queue.add("same"); queue.add("last");
        ExtrasSession session = session(new Intent().putExtra("queue", queue).putExtra("date", new java.sql.Date(10)));
        String queueId = node(session.children("root"), "queue").getString("id");
        JSONArray items = session.children(queueId);
        assertNotEquals(items.getJSONObject(0).getString("id"), items.getJSONObject(1).getString("id"));
        session.set(items.getJSONObject(1).getString("id"), "second", false);
        session.set(node(session.children("root"), "date").getString("id"), "1234", false);
        assertArrayEquals(new Object[]{"same", "second", "last"}, ((java.util.ArrayDeque<?>)decode(session).getSerializableExtra("queue")).toArray());
        Object restored = decode(session).getSerializableExtra("date");
        assertEquals(java.sql.Date.class, restored.getClass()); assertEquals(1234L, ((java.util.Date)restored).getTime());
    }

    private static class Parent { long inherited; }
    public static class PostData extends Parent implements Parcelable {
        private String title;
        private int quantity;
        PostData(String title, int quantity, long inherited) { this.title = title; this.quantity = quantity; this.inherited = inherited; }
        PostData(Parcel in) { title = in.readString(); quantity = in.readInt(); inherited = in.readLong(); }
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel out, int flags) { out.writeString(title); out.writeInt(quantity); out.writeLong(inherited); }
        public static final Creator<PostData> CREATOR = new Creator<PostData>() {
            public PostData createFromParcel(Parcel in) { return new PostData(in); }
            public PostData[] newArray(int size) { return new PostData[size]; }
        };
        @Override public String toString() { throw new AssertionError("Inspector must not call application toString"); }
    }
    public static class Graph implements Serializable {
        String name;
        Graph self, shared, alias;
        @Override public String toString() { throw new AssertionError("Inspector must not call application toString"); }
    }
    public static class Lossy implements Parcelable {
        String ignored = "fixed";
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel out, int flags) { out.writeInt(1); }
        public static final Creator<Lossy> CREATOR = new Creator<Lossy>() {
            public Lossy createFromParcel(Parcel in) { in.readInt(); return new Lossy(); }
            public Lossy[] newArray(int size) { return new Lossy[size]; }
        };
    }
    public static class SparseHolder implements Parcelable {
        transient int explicitTransient;
        android.util.SparseArray<String> values = new android.util.SparseArray<>();
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel out, int flags) { out.writeInt(explicitTransient); out.writeSparseArray(values); }
        public static final Creator<SparseHolder> CREATOR = new Creator<SparseHolder>() {
            public SparseHolder createFromParcel(Parcel in) {
                SparseHolder result = new SparseHolder(); result.explicitTransient = in.readInt();
                result.values = in.readSparseArray(SparseHolder.class.getClassLoader()); return result;
            }
            public SparseHolder[] newArray(int size) { return new SparseHolder[size]; }
        };
    }
}
