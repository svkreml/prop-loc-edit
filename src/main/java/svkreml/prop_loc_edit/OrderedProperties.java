package svkreml.prop_loc_edit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class OrderedProperties extends Properties {
    private final LinkedHashMap<Object, Object> linkedMap = new LinkedHashMap<>();

    @Override
    public Set<String> stringPropertyNames() {
        return linkedMap.keySet().stream().map(Object::toString).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return linkedMap.entrySet();
    }

    @Override
    public Enumeration<?> propertyNames() {
        return Collections.enumeration(stringPropertyNames());
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        String val = getProperty(key);
        return (val == null) ? defaultValue : val;
    }

    @Override
    public synchronized void putAll(Map<?, ?> t) {
        for (Map.Entry<?, ?> entry : t.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            put(key, value);
        }
    }


    @Override
    public synchronized void forEach(BiConsumer<? super Object, ? super Object> action) {
        linkedMap.forEach(action);
    }

    // Переопределяем для сохранения порядка
    @Override
    public synchronized Object put(Object key, Object value) {
        return linkedMap.put(key, value);
    }

    @Override
    public Object get(Object key) {
        return linkedMap.get(key);
    }

    @Override
    public Enumeration<Object> keys() {
        return Collections.enumeration(linkedMap.keySet());
    }

    @Override
    public Enumeration<Object> elements() {
        return Collections.enumeration(linkedMap.values());
    }

    @Override
    public synchronized void clear() {
        linkedMap.clear();
    }

    @Override
    public boolean contains(Object value) {
        return linkedMap.containsValue(value);
    }

    @Override
    public boolean containsKey(Object key) {
        return linkedMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return linkedMap.containsValue(value);
    }

    @Override
    public boolean isEmpty() {
        return linkedMap.isEmpty();
    }

    @Override
    public Set<Object> keySet() {
        return linkedMap.keySet();
    }

    @Override
    public void list(PrintStream out) {
        linkedMap.forEach((k, v) -> out.println(k + "=" + v));
    }

    @Override
    public int size() {
        return linkedMap.size();
    }

    @Override
    public Collection<Object> values() {
        return linkedMap.values();
    }

    // Сохраняем порядок при загрузке
    @Override
    public synchronized void load(InputStream inStream) throws IOException {
        super.load(inStream);
        linkedMap.putAll(this);
    }

    @Override
    public synchronized void load(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader parameter is null");
        super.load(reader);
        linkedMap.putAll(this);
    }

    // Сохраняем порядок при сохранении
    @Override
    public void store(OutputStream out, String comments) throws IOException {
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            store(writer, comments);
        }
    }

    @Override
    public void store(Writer writer, String comments)
            throws IOException {
        extracted(writer);
    }

    private void extracted(Writer writer) throws IOException {
        for (Map.Entry<Object, Object> entry : linkedMap.entrySet()) {
            writer.write(entry.getKey().toString().replace(" ", "\\ ") + "=" + entry.getValue().toString()
                    .replace("\\", "\\\\")
                    .replace("\n", "\\\n")
                    .replace(":", "\\:")
                    .replace("!", "\\!")
                    .replace("=", "\\=")
                         + "\n");
        }
    }

    @Override
    public String getProperty(String key) {
        Object oval = linkedMap.get(key);
        String sval = (oval instanceof String) ? (String) oval : null;
        Properties defaults;
        return ((sval == null) && ((defaults = this.defaults) != null)) ? defaults.getProperty(key) : sval;
    }
}
