package svkreml.prop_loc_edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class PropIo {

    private static final Logger LOG = Logger.getLogger(PropIo.class.getName());
    private static final String CSV_BOM = "\uFEFF";
    private static final char CSV_SEPARATOR = ';';

    private PropIo() {
    }

    public static final class Occurrence {
        public final int line;
        public final int lastLine;
        public final String key;
        public final String value;

        public Occurrence(int line, int lastLine, String key, String value) {
            this.line = line;
            this.lastLine = lastLine;
            this.key = key;
            this.value = value;
        }
    }

    public static final class LineEdit {
        public final int firstLine;
        public final int originalLastLine;
        public final List<String> newLines;

        public LineEdit(int firstLine, int originalLastLine, List<String> newLines) {
            this.firstLine = firstLine;
            this.originalLastLine = originalLastLine;
            this.newLines = newLines;
        }
    }

    private static final class LogicalLine {
        final int first;
        final int last;
        final String text;

        LogicalLine(int first, int last, String text) {
            this.first = first;
            this.last = last;
            this.text = text;
        }
    }

    public static List<String> findDuplicateKeys(String path) {
        return new ArrayList<>(findDuplicateOccurrences(path).keySet());
    }

    public static Map<String, List<Integer>> findDuplicateKeyLines(String path) {
        Map<String, List<Occurrence>> occurrences = findDuplicateOccurrences(path);
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Occurrence>> e : occurrences.entrySet()) {
            List<Integer> lines = new ArrayList<>();
            for (Occurrence o : e.getValue()) {
                lines.add(o.line);
            }
            result.put(e.getKey(), lines);
        }
        return result;
    }

    public static Map<String, List<Occurrence>> findDuplicateOccurrences(String path) {
        Map<String, List<Occurrence>> result = new LinkedHashMap<>();
        if (path == null || path.isEmpty()) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
            for (LogicalLine ll : toLogicalLines(lines)) {
                String[] kv = splitKeyValue(ll.text);
                if (kv == null) {
                    continue;
                }
                result.computeIfAbsent(kv[0], k -> new ArrayList<>())
                        .add(new Occurrence(ll.first, ll.last, kv[0], kv[1]));
            }
        } catch (IOException e) {
            LOG.warning("Failed to scan for duplicates: " + path + ": " + e.getMessage());
        }
        result.entrySet().removeIf(e -> e.getValue().size() < 2);
        return result;
    }

    public static int countLogicalKeys(List<String> physicalLines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LogicalLine ll : toLogicalLines(physicalLines)) {
            String[] kv = splitKeyValue(ll.text);
            if (kv == null) {
                continue;
            }
            counts.put(kv[0], counts.getOrDefault(kv[0], 0) + 1);
        }
        int result = 0;
        for (int c : counts.values()) {
            if (c > 1) {
                result += c;
            }
        }
        return result;
    }

    public static String formatPropertyLine(String key, String value) {
        return escapeKey(key == null ? "" : key) + "=" + escapeValue(value == null ? "" : value);
    }

    private static String escapeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == ' ' || c == '\t' || c == '\\' || c == '=' || c == ':' || c == '#' || c == '!' || c == '\n' || c == '\r') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapeValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        if (!value.isEmpty() && value.charAt(0) == ' ') {
            sb.insert(0, '\\');
        }
        return sb.toString();
    }

    private static List<LogicalLine> toLogicalLines(List<String> physical) {
        List<LogicalLine> out = new ArrayList<>();
        StringBuilder cur = null;
        int start = 0;
        for (int i = 0; i < physical.size(); i++) {
            String raw = physical.get(i);
            if (cur == null) {
                if (endsWithContinuation(raw)) {
                    start = i + 1;
                    cur = new StringBuilder(raw.substring(0, raw.length() - 1));
                } else {
                    out.add(new LogicalLine(i + 1, i + 1, raw));
                }
            } else {
                int k = 0;
                while (k < raw.length() && (raw.charAt(k) == ' ' || raw.charAt(k) == '\t')) {
                    k++;
                }
                if (endsWithContinuation(raw)) {
                    cur.append(raw, k, raw.length() - 1);
                } else {
                    cur.append(raw, k, raw.length());
                    out.add(new LogicalLine(start, i + 1, cur.toString()));
                    cur = null;
                }
            }
        }
        if (cur != null) {
            out.add(new LogicalLine(start, physical.size(), cur.toString()));
        }
        return out;
    }

    private static String[] splitKeyValue(String logicalText) {
        String s = logicalText.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) {
            return null;
        }
        int sep = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '=' || c == ':' || c == ' ' || c == '\t') && !isEscaped(s, i)) {
                sep = i;
                break;
            }
        }
        if (sep <= 0) {
            return null;
        }
        String key = unescape(s.substring(0, sep).trim());
        if (key.isEmpty()) {
            return null;
        }
        String value = s.substring(sep).trim();
        while (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == ':')) {
            value = value.substring(1).trim();
        }
        return new String[]{key, unescape(value)};
    }

    private static boolean isEscaped(String s, int idx) {
        int backslashes = 0;
        for (int j = idx - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static boolean endsWithContinuation(String raw) {
        int backslashes = 0;
        for (int j = raw.length() - 1; j >= 0 && raw.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 't':
                        sb.append('\t');
                        i++;
                        break;
                    case 'n':
                        sb.append('\n');
                        i++;
                        break;
                    case 'r':
                        sb.append('\r');
                        i++;
                        break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                                break;
                            } catch (NumberFormatException ignore) {
                                // падаем в default
                            }
                        }
                        sb.append(c);
                        break;
                    default:
                        sb.append(next);
                        i++;
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void applyLineEdits(Path path, Map<Integer, String> replacements) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (Map.Entry<Integer, String> e : replacements.entrySet()) {
            int idx = e.getKey() - 1;
            if (idx >= 0 && idx < lines.size()) {
                lines.set(idx, e.getValue());
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static List<String> applyEditsTo(List<String> lines, List<LineEdit> edits) {
        List<LineEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(e -> e.firstLine));
        List<String> out = new ArrayList<>();
        int cursor = 0;
        for (LineEdit e : sorted) {
            int startIdx = e.firstLine - 1;
            if (startIdx < cursor) {
                continue;
            }
            while (cursor < startIdx) {
                out.add(lines.get(cursor));
                cursor++;
            }
            cursor = Math.max(cursor, e.originalLastLine);
            out.addAll(e.newLines);
        }
        while (cursor < lines.size()) {
            out.add(lines.get(cursor));
            cursor++;
        }
        return out;
    }

    public static void applyEdits(Path path, List<LineEdit> edits) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Files.write(path, applyEditsTo(lines, edits), StandardCharsets.UTF_8);
    }

    public static void writeCsv(Path path, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder(CSV_BOM);
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(CSV_SEPARATOR);
                }
                sb.append(csvEscape(row[i]));
            }
            sb.append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<String[]> readCsv(Path path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            if (li == 0 && line.startsWith(CSV_BOM)) {
                line = line.substring(CSV_BOM.length());
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            rows.add(csvParse(line));
        }
        return rows;
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.indexOf(CSV_SEPARATOR) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (needQuote) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String[] csvParse(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == CSV_SEPARATOR) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}