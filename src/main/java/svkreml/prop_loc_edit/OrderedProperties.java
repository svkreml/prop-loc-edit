package svkreml.prop_loc_edit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OrderedProperties extends Properties {
    private final LinkedHashMap<Object, Object> linkedMap = new LinkedHashMap<>();

    @Override
    public Set<String> stringPropertyNames() {
        return super.stringPropertyNames();
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
    public void putAll(Map<? extends Object, ? extends Object> t) {
        for (Map.Entry<? extends Object, ? extends Object> entry : t.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            put(key, value);
        }
    }

    // Переопределяем для сохранения порядка
    @Override
    public Object put(Object key, Object value) {
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
    public void clear() {
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
        for (Map.Entry<Object, Object> entry : super.entrySet()) {
            linkedMap.put(entry.getKey(), entry.getValue());
        }
    }

    // Сохраняем порядок при сохранении
    @Override
    public void store(OutputStream out, String comments) throws IOException {
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<Object, Object> entry : linkedMap.entrySet()) {
                writer.write(entry.getKey().toString().replace(" ", "\\ ") + "=" + entry.getValue().toString().replace("\n", "\\\n") + "\n");
            }
        }
    }

    // То же самое для других методов, если нужно...
}