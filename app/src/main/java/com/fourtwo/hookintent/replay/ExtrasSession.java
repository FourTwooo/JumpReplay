package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.utils.IntentReplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A private, typed draft of an Intent. Only framework values/JSON leave this class;
 * application objects stay with their application ClassLoader. No arbitrary getter
 * or custom toString is invoked to inspect an object.
 */
public final class ExtrasSession {
    private final ClassLoader loader;
    private Intent intent;
    private Bundle extras;
    private String encoded;
    private int nextId;
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, String> paths = new HashMap<>();
    private final IdentityHashMap<Object, String> identities = new IdentityHashMap<>();

    public ExtrasSession(String parcelBase64, ClassLoader loader) {
        if (parcelBase64 == null || parcelBase64.isEmpty()) throw new IllegalArgumentException("缺少完整 Intent 快照");
        this.loader = loader == null ? ExtrasSession.class.getClassLoader() : loader;
        restore(parcelBase64);
        nodes.put("root", new Node("root", "Extras", new ArrayList<>(), Bundle.class));
        paths.put("", "root");
    }

    public synchronized String parcel() { return encoded; }

    public synchronized String uri() { return intent.toUri(Intent.URI_INTENT_SCHEME); }

    /** Merge URI edits where custom classes are available, then retain only a fresh parcel. */
    public synchronized String merge(String text, String originalText) throws Exception {
        String before = encoded;
        try {
            Intent merged = IntentReplay.fromText(text, intent, originalText);
            restore(IntentPayloadCodec.encode(merged));
            nodes.clear(); paths.clear(); nextId = 0;
            nodes.put("root", new Node("root", "Extras", new ArrayList<>(), Bundle.class));
            paths.put("", "root");
            return encoded;
        } catch (Throwable error) {
            restore(before);
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalArgumentException("对象拒绝了 URI 修改: " + error.getClass().getSimpleName(), error);
        }
    }

    /** Describes one level at a time, without truncating nested values or object graphs. */
    public synchronized JSONArray children(String nodeId) throws Exception {
        Node parent = requireNode(nodeId);
        Object value = get(parent.path);
        JSONArray result = new JSONArray();
        if (value == null) return result;
        identities.putIfAbsent(value, nodeId);
        if (value instanceof Bundle) {
            Bundle bundle = (Bundle) value;
            bundle.setClassLoader(loader);
            ArrayList<String> keys = new ArrayList<>(bundle.keySet());
            keys.sort(String::compareTo);
            for (String key : keys) add(result, parent, key, new BundleStep(key), null);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++)
                add(result, parent, "[" + i + "]", new IndexStep(i), value.getClass().getComponentType());
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) add(result, parent, "[" + i + "]", new IndexStep(i), null);
        } else if (value instanceof Map) {
            for (Object key : ((Map<?, ?>) value).keySet()) {
                String label = key == null ? "null" : scalar(key.getClass()) ? scalarText(key) : "键: " + key.getClass().getName();
                MapStep lookup = new MapStep(key);
                add(result, parent, label, lookup, null);
                add(result, parent, "[key] " + label, new MapKeyStep(lookup), null);
            }
        } else if (value instanceof Collection) {
            int index = 0;
            Map<String, Integer> occurrences = new HashMap<>();
            for (Object element : (Collection<?>) value) {
                byte[] stamp = MapStep.keyBytes(element);
                String stampKey = Base64.encodeToString(stamp, Base64.NO_WRAP);
                int occurrence = occurrences.getOrDefault(stampKey, 0);
                occurrences.put(stampKey, occurrence + 1);
                add(result, parent, "[" + index++ + "]", new CollectionStep(stamp, occurrence), null);
            }
        } else if (sparse(value)) {
            for (int index = 0; index < sparseSize(value); index++) {
                long key = sparseKey(value, index);
                Class<?> declared = value instanceof SparseBooleanArray ? boolean.class
                        : value instanceof SparseIntArray ? int.class : value instanceof SparseLongArray ? long.class : null;
                add(result, parent, "[" + key + "]", new SparseStep(key), declared);
            }
        } else if (expandable(value)) {
            ArrayList<Field> fields = new ArrayList<>();
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    // Parcelable codecs may explicitly persist transient Java fields.
                    // The subsequent codec verification decides whether an edit survives.
                    if (!Modifier.isStatic(modifiers) && !field.isSynthetic()) fields.add(field);
                }
            }
            fields.sort(Comparator.comparing((Field field) -> field.getName()).thenComparing(field -> field.getDeclaringClass().getName()));
            for (Field field : fields) add(result, parent, field.getName(), new FieldStep(field), field.getType());
        }
        return result;
    }

    public synchronized String set(String nodeId, String text, boolean makeNull) throws Exception {
        return setTyped(nodeId, null, text, makeNull);
    }

    /** Explicit types are only accepted for previously untyped null container entries. */
    public synchronized String setTyped(String nodeId, String requestedType, String text, boolean makeNull) throws Exception {
        Node node = requireNode(nodeId);
        if (node.path.isEmpty()) throw new IllegalArgumentException("不能替换整个 Extras 容器");
        Object previous = get(node.path);
        Class<?> declared = node.declared;
        Class<?> type = previous != null ? previous.getClass() : declared;
        if (requestedType != null && !requestedType.isEmpty()) {
            Class<?> requested = explicitType(requestedType);
            if (previous != null || declared != null && declared != Object.class) {
                if (boxed(type) != requested) throw new IllegalArgumentException("不能更改已有字段的类型");
            } else type = requested;
        }
        if (makeNull && declared != null && declared.isPrimitive()) throw new IllegalArgumentException("基本类型不能设置为 null");
        if (!makeNull && (type == null || type == Object.class)) throw new IllegalArgumentException("请选择 null 参数的新值类型");
        Object replacement = makeNull ? null : parse(type, text == null ? "" : text);
        if (declared != null && replacement != null && !boxed(declared).isInstance(replacement))
            throw new IllegalArgumentException("新值与字段声明类型不兼容: " + declared.getName());
        String before = encoded;
        Map<CollectionStep, CollectionPosition> collectionPositions = new IdentityHashMap<>();
        Map<MapStep, byte[]> mapPositions = new IdentityHashMap<>();
        ArrayList<KeyEdit> keyEdits = new ArrayList<>();
        for (int index = 0; index < node.path.size(); index++) {
            Step step = node.path.get(index);
            if (step instanceof CollectionStep)
                collectionPositions.put((CollectionStep) step, new CollectionPosition((CollectionStep) step));
            if (step instanceof MapKeyStep) {
                MapKeyStep key = (MapKeyStep) step;
                keyEdits.add(new KeyEdit(key, index, pathKey(node.path.subList(0, index)), key.lookup.fingerprint));
            }
        }
        try {
            put(node.path, replacement);
            for (int index = keyEdits.size() - 1; index >= 0; index--) {
                KeyEdit edit = keyEdits.get(index);
                edit.step.rehash();
                byte[] changed = MapStep.keyBytes(edit.step.resolved);
                for (Node candidate : nodes.values()) {
                    if (candidate.path.size() <= edit.index || !edit.prefix.equals(pathKey(candidate.path.subList(0, edit.index)))) continue;
                    Step candidateStep = candidate.path.get(edit.index);
                    MapStep lookup = candidateStep instanceof MapStep ? (MapStep) candidateStep
                            : candidateStep instanceof MapKeyStep ? ((MapKeyStep) candidateStep).lookup : null;
                    if (lookup != null && Arrays.equals(lookup.fingerprint, edit.before)) {
                        mapPositions.putIfAbsent(lookup, lookup.fingerprint);
                        lookup.fingerprint = changed;
                    }
                }
            }
            // Editing an element changes its lookup fingerprint (including when a
            // field inside a Set element changes). Rebase before verifying its codec.
            for (CollectionStep step : collectionPositions.keySet()) step.rebase();
            intent.replaceExtras(extras);
            String proposed = IntentPayloadCodec.encode(intent);
            restore(proposed);
            Object actual = get(node.path);
            if (!sameScalar(replacement, actual))
                throw new IllegalArgumentException("该字段的修改没有被对象的编解码逻辑保留，已撤销修改");
            rebuildPaths();
            return encoded;
        } catch (Throwable error) {
            for (Map.Entry<CollectionStep, CollectionPosition> position : collectionPositions.entrySet()) {
                position.getKey().fingerprint = position.getValue().fingerprint;
                position.getKey().occurrence = position.getValue().occurrence;
            }
            for (Map.Entry<MapStep, byte[]> position : mapPositions.entrySet()) position.getKey().fingerprint = position.getValue();
            restore(before);
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalArgumentException("对象拒绝了修改: " + error.getClass().getSimpleName(), error);
        }
    }

    private void restore(String value) {
        Bundle record = new Bundle(); record.putString(IntentPayloadCodec.KEY, value);
        Intent restored = IntentPayloadCodec.decode(record);
        restored.setExtrasClassLoader(loader);
        Bundle restoredExtras = restored.getExtras();
        if (restoredExtras == null) restoredExtras = new Bundle();
        restoredExtras.setClassLoader(loader);
        // Force all top-level values to decode while the correct loader is available.
        // In Android 13+ this also resolves lazily stored values independently.
        for (String key : restoredExtras.keySet()) restoredExtras.get(key);
        intent = restored;
        extras = restoredExtras;
        encoded = value;
        identities.clear();
        identities.put(extras, "root");
    }

    private Node requireNode(String id) {
        Node node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("参数节点已失效，请重新打开参数列表");
        return node;
    }

    private void add(JSONArray result, Node parent, String name, Step step, Class<?> declared) throws Exception {
        ArrayList<Step> path = new ArrayList<>(parent.path); path.add(step);
        String key = pathKey(path);
        String id = paths.get(key);
        if (id == null) { id = Integer.toString(++nextId); paths.put(key, id); }
        Node node = new Node(id, name, path, declared);
        nodes.put(id, node);
        JSONObject data = new JSONObject().put("id", id).put("name", name);
        try {
            Object value = get(path);
            Class<?> type = value != null ? value.getClass() : declared;
            boolean simple = type != null && scalar(type);
            boolean expand = expandable(value);
            data.put("type", value instanceof Enum ? ((Enum<?>) value).getDeclaringClass().getName()
                    : type == null ? "null" : type.getName());
            data.put("isNull", value == null);
            data.put("value", value == null ? "null" : simple ? scalarText(value) : preview(value));
            data.put("editable", simple || value == null && (declared == null || declared == Object.class));
            data.put("nullable", declared == null || !declared.isPrimitive());
            data.put("untyped", value == null && (declared == null || declared == Object.class));
            data.put("expandable", expand);
            if (value instanceof Enum || type != null && boxed(type).isEnum()) {
                JSONArray choices = new JSONArray();
                Class<?> enumeration = value instanceof Enum ? ((Enum<?>) value).getDeclaringClass() : type;
                for (Object choice : enumeration.getEnumConstants()) choices.put(((Enum<?>) choice).name());
                data.put("choices", choices);
            }
            if (expand) {
                String reference = identities.get(value);
                if (reference != null && !reference.equals(id)) data.put("reference", reference);
                else identities.put(value, id);
            } else if (!simple && value != null) data.put("reason", value instanceof Collection
                    ? "此 Collection 没有稳定的位置或键，不能安全定位元素进行修改"
                    : "此类型包含运行时资源或没有可安全展开的字段");
            else if (value == null && !simple && declared != null && declared != Object.class)
                data.put("reason", "该自定义对象为 null，创建实例需要应用自己的构造规则");
        } catch (Exception error) {
            data.put("type", declared == null ? "unknown" : declared.getName());
            data.put("value", "无法读取").put("editable", false).put("expandable", false).put("nullable", false);
            data.put("reason", error.getClass().getSimpleName() + ": " + safeMessage(error));
        }
        result.put(data);
    }

    private Object get(List<Step> path) throws Exception {
        Object value = extras;
        for (Step step : path) {
            if (value == null) throw new IllegalStateException("父对象已经为 null，请刷新参数列表");
            value = step.get(value);
        }
        return value;
    }

    private void put(List<Step> path, Object value) throws Exception {
        Object owner = extras;
        for (int i = 0; i < path.size() - 1; i++) owner = path.get(i).get(owner);
        path.get(path.size() - 1).set(owner, value);
    }

    private static String pathKey(List<Step> steps) {
        StringBuilder out = new StringBuilder();
        for (Step step : steps) out.append('/').append(step.key());
        return out.toString();
    }

    private void rebuildPaths() {
        paths.clear();
        for (Node node : nodes.values()) paths.put(pathKey(node.path), node.id);
    }

    private static boolean expandable(Object value) {
        if (value == null || scalar(value.getClass()) || value instanceof IBinder || value instanceof FileDescriptor
                || value instanceof android.os.ParcelFileDescriptor || value instanceof android.app.PendingIntent) return false;
        if (value instanceof Bundle || value.getClass().isArray() || value instanceof List || value instanceof Map
                || value instanceof Collection || sparse(value)) return true;
        String name = value.getClass().getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") || name.startsWith("dalvik."));
    }

    private static String preview(Object value) {
        if (value instanceof Bundle) return "Bundle";
        if (value.getClass().isArray()) return Array.getLength(value) + " 项";
        if (value instanceof Collection) return ((Collection<?>) value).size() + " 项";
        if (value instanceof Map) return ((Map<?, ?>) value).size() + " 项";
        if (sparse(value)) return sparseSize(value) + " 项";
        return "对象";
    }

    private static boolean scalar(Class<?> type) {
        type = boxed(type);
        return type == String.class || type == Boolean.class || type == Character.class || type == Byte.class
                || type == Short.class || type == Integer.class || type == Long.class || type == Float.class
                || type == Double.class || type == BigInteger.class || type == BigDecimal.class || Enum.class.isAssignableFrom(type)
                || Uri.class.isAssignableFrom(type) || Date.class.isAssignableFrom(type) || type == ComponentName.class;
    }

    private static String scalarText(Object value) {
        if (value instanceof Enum) return ((Enum<?>) value).name();
        if (value instanceof Date) return Long.toString(((Date) value).getTime());
        if (value instanceof ComponentName) return ((ComponentName) value).flattenToString();
        return String.valueOf(value); // Only known scalar classes reach this method.
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object parse(Class<?> type, String text) {
        type = boxed(type);
        while (Enum.class.isAssignableFrom(type) && !type.isEnum()) type = type.getSuperclass();
        try {
            if (type == String.class) return text;
            if (type == Character.class) {
                if (text.length() != 1) throw new IllegalArgumentException("Character 必须恰好包含一个 UTF-16 字符");
                return text.charAt(0);
            }
            if (type == Boolean.class) {
                if (!"true".equals(text) && !"false".equals(text)) throw new IllegalArgumentException("Boolean 只能为 true 或 false");
                return Boolean.valueOf(text);
            }
            if (type == Byte.class) return Byte.valueOf(text);
            if (type == Short.class) return Short.valueOf(text);
            if (type == Integer.class) return Integer.valueOf(text);
            if (type == Long.class) return Long.valueOf(text);
            if (type == Float.class) {
                Float number = Float.valueOf(text);
                if (!Float.isFinite(number) && !"NaN".equals(text) && !"Infinity".equals(text) && !"-Infinity".equals(text))
                    throw new NumberFormatException("Float overflow");
                return number;
            }
            if (type == Double.class) {
                Double number = Double.valueOf(text);
                if (!Double.isFinite(number) && !"NaN".equals(text) && !"Infinity".equals(text) && !"-Infinity".equals(text))
                    throw new NumberFormatException("Double overflow");
                return number;
            }
            if (type == BigInteger.class) return new BigInteger(text);
            if (type == BigDecimal.class) return new BigDecimal(text);
            if (Date.class.isAssignableFrom(type)) {
                try { return type.getConstructor(long.class).newInstance(Long.parseLong(text)); }
                catch (ReflectiveOperationException error) { throw new IllegalArgumentException("此日期类型没有可用的毫秒时间戳构造方法", error); }
            }
            if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, text);
            if (Uri.class.isAssignableFrom(type)) return Uri.parse(text);
            if (type == ComponentName.class) {
                ComponentName component = ComponentName.unflattenFromString(text);
                if (component == null) throw new IllegalArgumentException("ComponentName 需要 package/class 格式");
                return component;
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("数值格式不正确或超过 " + type.getSimpleName() + " 的范围", error);
        }
        throw new IllegalArgumentException("请展开对象并修改具体字段，不能用字符串替换 " + type.getName());
    }

    private static boolean sameScalar(Object expected, Object actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected instanceof Uri && actual instanceof Uri) return expected.equals(actual);
        if (expected.getClass() != actual.getClass()) return false;
        if (expected instanceof Float) return Float.floatToIntBits((Float) expected) == Float.floatToIntBits((Float) actual);
        if (expected instanceof Double) return Double.doubleToLongBits((Double) expected) == Double.doubleToLongBits((Double) actual);
        return expected.equals(actual);
    }

    private static Class<?> explicitType(String type) {
        switch (type) {
            case "java.lang.String": return String.class;
            case "java.lang.Boolean": return Boolean.class;
            case "java.lang.Character": return Character.class;
            case "java.lang.Byte": return Byte.class;
            case "java.lang.Short": return Short.class;
            case "java.lang.Integer": return Integer.class;
            case "java.lang.Long": return Long.class;
            case "java.lang.Float": return Float.class;
            case "java.lang.Double": return Double.class;
            default: throw new IllegalArgumentException("不支持的新值类型: " + type);
        }
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }

    private static String safeMessage(Throwable error) { return error.getMessage() == null ? "" : error.getMessage(); }
    private static String encodeKey(String value) { return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP); }

    private static final class Node {
        final String id, name;
        final ArrayList<Step> path;
        final Class<?> declared;
        Node(String id, String name, ArrayList<Step> path, Class<?> declared) {
            this.id = id; this.name = name; this.path = path; this.declared = declared;
        }
    }

    private interface Step {
        Object get(Object owner) throws Exception;
        void set(Object owner, Object value) throws Exception;
        String key();
    }

    private static final class BundleStep implements Step {
        final String name;
        BundleStep(String name) { this.name = name; }
        public Object get(Object owner) { return ((Bundle) owner).get(name); }
        public void set(Object owner, Object value) { putBundle((Bundle) owner, name, value); }
        public String key() { return "b:" + encodeKey(name); }
    }

    private static final class IndexStep implements Step {
        final int index;
        IndexStep(int index) { this.index = index; }
        public Object get(Object owner) { return owner.getClass().isArray() ? Array.get(owner, index) : ((List<?>) owner).get(index); }
        @SuppressWarnings({"rawtypes", "unchecked"}) public void set(Object owner, Object value) {
            if (owner.getClass().isArray()) Array.set(owner, index, value);
            else ((List) owner).set(index, value);
        }
        public String key() { return "i:" + index; }
    }

    private static final class FieldStep implements Step {
        final Field field;
        FieldStep(Field field) { this.field = field; }
        public Object get(Object owner) throws IllegalAccessException { field.setAccessible(true); return field.get(owner); }
        public void set(Object owner, Object value) throws IllegalAccessException { field.setAccessible(true); field.set(owner, value); }
        public String key() { return "f:" + encodeKey(field.getDeclaringClass().getName() + "#" + field.getName()); }
    }

    /** Fingerprints keys rather than relying on HashMap iteration order after re-decoding. */
    private static final class MapStep implements Step {
        byte[] fingerprint;
        MapStep(Object key) { fingerprint = keyBytes(key); }
        private Object find(Map<?, ?> map) {
            Object found = null; int matches = 0;
            for (Object key : map.keySet()) if (Arrays.equals(fingerprint, keyBytes(key))) { found = key; matches++; }
            if (matches > 1) throw new IllegalStateException("Map 包含序列化内容相同但身份不同的键，无法安全定位此值");
            if (matches == 0) throw new IllegalStateException("Map 的键在编解码后发生变化，无法安全定位此值");
            return found;
        }
        public Object get(Object owner) { Map<?, ?> map = (Map<?, ?>) owner; return map.get(find(map)); }
        @SuppressWarnings({"unchecked", "rawtypes"}) public void set(Object owner, Object value) { Map map = (Map) owner; map.put(find(map), value); }
        public String key() { return "m:" + Base64.encodeToString(fingerprint, Base64.NO_WRAP); }
        private static byte[] keyBytes(Object value) {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeValue(value);
                if (parcel.hasFileDescriptors()) throw new IllegalArgumentException("Map 的键包含文件描述符");
                return parcel.marshall();
            } finally { parcel.recycle(); }
        }
    }

    private static final class CollectionStep implements Step {
        byte[] fingerprint;
        int occurrence;
        int position;
        Object resolved;
        Collection<?> owner;
        CollectionStep(byte[] fingerprint, int occurrence) { this.fingerprint = fingerprint; this.occurrence = occurrence; }
        private Object find(Collection<?> collection) {
            int matches = 0, index = 0; boolean found = false;
            for (Object element : collection) {
                if (Arrays.equals(fingerprint, MapStep.keyBytes(element))) {
                    if (matches == occurrence) { resolved = element; position = index; found = true; }
                    matches++;
                }
                index++;
            }
            if (collection instanceof Set && matches > 1)
                throw new IllegalStateException("Set 包含序列化内容相同但身份不同的元素，无法安全定位");
            if (!found) throw new IllegalStateException("Collection 元素在编解码后发生变化，无法安全定位");
            owner = collection; return resolved;
        }
        public Object get(Object owner) { return find((Collection<?>) owner); }
        @SuppressWarnings({"rawtypes", "unchecked"}) public void set(Object owner, Object value) {
            Collection collection = (Collection) owner;
            find(collection);
            ArrayList<Object> items = new ArrayList<>(); int index = 0;
            for (Object item : collection) {
                if (index++ == position) items.add(value);
                else items.add(item);
            }
            collection.clear(); collection.addAll(items);
            if (collection.size() != items.size()) throw new IllegalArgumentException("修改造成了集合元素重复，已撤销修改");
            resolved = value;
        }
        void rebase() {
            fingerprint = MapStep.keyBytes(resolved);
            int index = 0, identical = 0, firstIdentityPosition = -1;
            for (Object element : owner) {
                if (element == resolved) {
                    if (firstIdentityPosition < 0) firstIdentityPosition = index;
                    if (index == position) { firstIdentityPosition = position; break; }
                }
                index++;
            }
            index = 0;
            for (Object element : owner) {
                if (index++ == firstIdentityPosition) break;
                if (Arrays.equals(fingerprint, MapStep.keyBytes(element))) identical++;
            }
            occurrence = identical;
        }
        public String key() { return "s:" + occurrence + ":" + Base64.encodeToString(fingerprint, Base64.NO_WRAP); }
    }

    private static final class CollectionPosition {
        final byte[] fingerprint;
        final int occurrence;
        CollectionPosition(CollectionStep step) { fingerprint = step.fingerprint; occurrence = step.occurrence; }
    }

    private static final class MapKeyStep implements Step {
        final MapStep lookup;
        Map<Object, Object> owner;
        Object resolved;
        MapKeyStep(MapStep lookup) { this.lookup = lookup; }
        @SuppressWarnings("unchecked") public Object get(Object owner) {
            this.owner = (Map<Object, Object>) owner;
            return resolved = lookup.find(this.owner);
        }
        public void set(Object owner, Object value) {
            Object previous = get(owner);
            if (this.owner.containsKey(value) && !java.util.Objects.equals(previous, value))
                throw new IllegalArgumentException("Map 中已经存在此键，已撤销修改");
            Object item = this.owner.remove(previous);
            this.owner.put(value, item);
            resolved = value;
        }
        void rehash() {
            // Mutating a field in a key can change its hash/comparison. Reinsert
            // using the same Map instance so aliases and its concrete type survive.
            ArrayList<Map.Entry<Object, Object>> entries = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : owner.entrySet())
                entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(entry));
            owner.clear();
            for (Map.Entry<Object, Object> entry : entries) owner.put(entry.getKey(), entry.getValue());
            if (owner.size() != entries.size()) throw new IllegalArgumentException("修改造成了 Map 键冲突，已撤销修改");
        }
        public String key() { return "k:" + Base64.encodeToString(lookup.fingerprint, Base64.NO_WRAP); }
    }

    private static final class KeyEdit {
        final MapKeyStep step;
        final int index;
        final String prefix;
        final byte[] before;
        KeyEdit(MapKeyStep step, int index, String prefix, byte[] before) {
            this.step = step; this.index = index; this.prefix = prefix; this.before = before;
        }
    }

    private static boolean sparse(Object value) {
        return value instanceof SparseArray || value instanceof LongSparseArray || value instanceof SparseBooleanArray
                || value instanceof SparseIntArray || value instanceof SparseLongArray;
    }
    private static int sparseSize(Object value) {
        if (value instanceof SparseArray) return ((SparseArray<?>) value).size();
        if (value instanceof LongSparseArray) return ((LongSparseArray<?>) value).size();
        if (value instanceof SparseBooleanArray) return ((SparseBooleanArray) value).size();
        if (value instanceof SparseIntArray) return ((SparseIntArray) value).size();
        return ((SparseLongArray) value).size();
    }
    private static long sparseKey(Object value, int index) {
        if (value instanceof SparseArray) return ((SparseArray<?>) value).keyAt(index);
        if (value instanceof LongSparseArray) return ((LongSparseArray<?>) value).keyAt(index);
        if (value instanceof SparseBooleanArray) return ((SparseBooleanArray) value).keyAt(index);
        if (value instanceof SparseIntArray) return ((SparseIntArray) value).keyAt(index);
        return ((SparseLongArray) value).keyAt(index);
    }
    private static final class SparseStep implements Step {
        final long key;
        SparseStep(long key) { this.key = key; }
        public Object get(Object owner) {
            if (owner instanceof SparseArray) return ((SparseArray<?>) owner).get((int) key);
            if (owner instanceof LongSparseArray) return ((LongSparseArray<?>) owner).get(key);
            if (owner instanceof SparseBooleanArray) return ((SparseBooleanArray) owner).get((int) key);
            if (owner instanceof SparseIntArray) return ((SparseIntArray) owner).get((int) key);
            return ((SparseLongArray) owner).get((int) key);
        }
        @SuppressWarnings({"rawtypes", "unchecked"}) public void set(Object owner, Object value) {
            if (owner instanceof SparseArray) ((SparseArray) owner).put((int) key, value);
            else if (owner instanceof LongSparseArray) ((LongSparseArray) owner).put(key, value);
            else if (owner instanceof SparseBooleanArray) ((SparseBooleanArray) owner).put((int) key, (Boolean) value);
            else if (owner instanceof SparseIntArray) ((SparseIntArray) owner).put((int) key, (Integer) value);
            else ((SparseLongArray) owner).put((int) key, (Long) value);
        }
        public String key() { return "p:" + key; }
    }

    private static void putBundle(Bundle bundle, String key, Object value) {
        if (value == null) bundle.putString(key, null);
        else if (value instanceof String) bundle.putString(key, (String) value);
        else if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
        else if (value instanceof Byte) bundle.putByte(key, (Byte) value);
        else if (value instanceof Short) bundle.putShort(key, (Short) value);
        else if (value instanceof Integer) bundle.putInt(key, (Integer) value);
        else if (value instanceof Long) bundle.putLong(key, (Long) value);
        else if (value instanceof Float) bundle.putFloat(key, (Float) value);
        else if (value instanceof Double) bundle.putDouble(key, (Double) value);
        else if (value instanceof Character) bundle.putChar(key, (Character) value);
        else if (value instanceof Bundle) bundle.putBundle(key, (Bundle) value);
        else if (value instanceof Parcelable) bundle.putParcelable(key, (Parcelable) value);
        else if (value instanceof Serializable) bundle.putSerializable(key, (Serializable) value);
        else throw new IllegalArgumentException("无法写入 Bundle 的类型: " + value.getClass().getName());
    }
}
