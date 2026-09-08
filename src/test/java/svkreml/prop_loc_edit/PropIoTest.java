package svkreml.prop_loc_edit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PropIoTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void findDuplicateKeys_findsDuplicates() throws IOException {
        Path file = new File(temp.getRoot(), "dup.properties").toPath();
        Files.write(file, (
                "# comment\n" +
                "key1 = value1\n" +
                "key2:value2\n" +
                "key1=repeat\n" +
                "key3 repeat3\n" +
                "\n" +
                "key4=value4"
        ).getBytes(StandardCharsets.UTF_8));

        List<String> duplicates = PropIo.findDuplicateKeys(file.toString());
        assertEquals(1, duplicates.size());
        assertEquals("key1", duplicates.get(0));
    }

    @Test
    public void findDuplicateKeys_emptyFile_noDuplicates() throws IOException {
        File file = new File(temp.getRoot(), "empty.properties");
        Files.write(file.toPath(), "# just a comment\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(PropIo.findDuplicateKeys(file.toString()).isEmpty());
    }

    @Test
    public void findDuplicateKeys_missingFile_noDuplicates() {
        assertTrue(PropIo.findDuplicateKeys(new File(temp.getRoot(), "nope.properties").toString()).isEmpty());
    }

    @Test
    public void findDuplicateKeyLines_reportsLineNumbers() throws IOException {
        Path file = new File(temp.getRoot(), "dup_lines.properties").toPath();
        Files.write(file, (
                "# comment\n" +
                "key1 = value1\n" +
                "key2:value2\n" +
                "key1=repeat\n" +
                "\n" +
                "key1 third"
        ).getBytes(StandardCharsets.UTF_8));

        Map<String, List<Integer>> result = PropIo.findDuplicateKeyLines(file.toString());
        assertEquals(1, result.size());
        List<Integer> lines = result.get("key1");
        assertTrue(lines != null);
        assertEquals(List.of(2, 4, 6), lines);
    }

    @Test
    public void findDuplicateOccurrences_returnsKeysAndValues() throws IOException {
        Path file = new File(temp.getRoot(), "dup_vals.properties").toPath();
        Files.write(file, (
                "config.host = 127.0.0.1\n" +
                "child.name = Сын\n" +
                "config.host: other-host\n"
        ).getBytes(StandardCharsets.UTF_8));

        Map<String, List<PropIo.Occurrence>> result = PropIo.findDuplicateOccurrences(file.toString());
        assertEquals(1, result.size());
        List<PropIo.Occurrence> occ = result.get("config.host");
        assertTrue(occ != null);
        assertEquals(2, occ.size());
        assertEquals(1, occ.get(0).line);
        assertEquals("127.0.0.1", occ.get(0).value);
        assertEquals(3, occ.get(1).line);
        assertEquals("other-host", occ.get(1).value);
    }

    @Test
    public void applyLineEdits_replacesOnlyGivenLines() throws IOException {
        Path file = new File(temp.getRoot(), "edit.properties").toPath();
        Files.write(file, ("# comment\nk1 = v1\n\nk2:v2\n").getBytes(StandardCharsets.UTF_8));

        java.util.Map<Integer, String> edits = new java.util.HashMap<>();
        edits.put(2, "k1_renamed=v1");
        PropIo.applyLineEdits(file, edits);

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains("# comment"));
        assertTrue(text.contains("k1_renamed=v1"));
        assertTrue(text.contains("k2:v2"));
    }

    @Test
    public void findDuplicateOccurrences_joinsMultilineContinuations() throws IOException {
        Path file = new File(temp.getRoot(), "multi.properties").toPath();
        Files.write(file, (
                "unmanagedAttributePolicy.DISABLED=Disabled\n" +
                "unmanagedAttributesHelpText=Unmanaged attributes are user attributes not explicitly defined in the user profile configuration. \\\n" +
                "  By default, unmanaged attributes are created as read-only for all users. \\\n" +
                "  By setting a user attribute \\\n" +
                "  management option to write-capable, the attribute becomes undirected.\n" +
                "unmanagedAttributesHelpText=second occurrence of the same key\n"
        ).getBytes(StandardCharsets.UTF_8));

        String joined = "Unmanaged attributes are user attributes not explicitly defined in the user profile configuration. "
                + "By default, unmanaged attributes are created as read-only for all users. "
                + "By setting a user attribute "
                + "management option to write-capable, the attribute becomes undirected.";

        Map<String, List<PropIo.Occurrence>> result = PropIo.findDuplicateOccurrences(file.toString());
        assertEquals(1, result.size());
        List<PropIo.Occurrence> occ = result.get("unmanagedAttributesHelpText");
        assertTrue(occ != null);
        assertEquals(2, occ.size());
        PropIo.Occurrence first = occ.get(0);
        assertEquals(2, first.line);
        assertEquals(5, first.lastLine);
        assertEquals(joined, first.value);
        assertEquals(6, occ.get(1).line);
        assertEquals(6, occ.get(1).lastLine);
    }

    @Test
    public void countLogicalKeys_countsJoinedLogicalLines() throws IOException {
        Path file = new File(temp.getRoot(), "multi_count.properties").toPath();
        Files.write(file, (
                "help.long = one \\\n" +
                "  two \\\n" +
                "  three\n" +
                "help.dup = x\n" +
                "help.long = again\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int result = PropIo.countLogicalKeys(lines);
        assertEquals(2, result);
    }

    @Test
    public void formatPropertyLine_escapesMultilineValue() {
        String line = PropIo.formatPropertyLine("unmanagedAttributesHelpText", "Line1\nLine2\nLine3");
        assertEquals("unmanagedAttributesHelpText=Line1\\nLine2\\nLine3", line);
    }

    @Test
    public void applyEdits_replacesWholeLogicalLineRange() throws IOException {
        Path file = new File(temp.getRoot(), "edit_multi.properties").toPath();
        Files.write(file, ("k=one \\\n  two\nother=x\n").getBytes(StandardCharsets.UTF_8));

        PropIo.applyEdits(file, List.of(new PropIo.LineEdit(1, 2, List.of("k=new"))));

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals("k=new\nother=x\n", text);
    }

    @Test
    public void findDuplicateOccurrences_handlesEscapedSeparatorsInKeys() throws IOException {
        Path file = new File(temp.getRoot(), "escaped_keys.properties").toPath();
        Files.write(file, (
                "requestObject.request\\ or\\ request_uri=Request or Request URI\n" +
                "other=1\n" +
                "requestObject.request\\ or\\ request_uri=Again\n"
        ).getBytes(StandardCharsets.UTF_8));

        Map<String, List<PropIo.Occurrence>> result = PropIo.findDuplicateOccurrences(file.toString());
        assertEquals(1, result.size());
        List<PropIo.Occurrence> occ = result.get("requestObject.request or request_uri");
        assertTrue(occ != null);
        assertEquals(2, occ.size());
        assertEquals("Request or Request URI", occ.get(0).value);
        assertEquals("Again", occ.get(1).value);
    }

    @Test
    public void formatPropertyLine_readAndWriteAreAnalogous() throws Exception {
        String line = PropIo.formatPropertyLine(
                "requestObject.request or request_uri", "Request or Request URI");
        assertEquals("requestObject.request\\ or\\ request_uri=Request or Request URI", line);

        Properties p = new Properties();
        p.load(new StringReader(line));
        assertEquals("requestObject.request or request_uri",
                p.stringPropertyNames().iterator().next());
        assertEquals("Request or Request URI",
                p.getProperty("requestObject.request or request_uri"));

        String esc = PropIo.formatPropertyLine("esc", "a\nb\tc\\d");
        Properties p2 = new Properties();
        p2.load(new StringReader(esc));
        assertEquals("a\nb\tc\\d", p2.getProperty("esc"));
    }

    @Test
    public void countLogicalKeys_withEscapedSpaces_noFalseDuplicates() throws IOException {
        Path file = new File(temp.getRoot(), "escaped_count.properties").toPath();
        Files.write(file, (
                "requestObject.request\\ or\\ request_uri=Request or Request URI\n" +
                "other = 1\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(0, PropIo.countLogicalKeys(lines));
    }

    @Test
    public void writeCsv_readCsv_roundTrip() throws IOException {
        File csv = new File(temp.getRoot(), "out.csv");
        java.util.List<String[]> rows = java.util.List.of(
                new String[]{"key", "english", "russian"},
                new String[]{"login.title", "Log in", "Вход в систему"},
                new String[]{"note.tricky", "He said \"hi\"; ok", "Он сказал \"привет\"; ок"}
        );
        PropIo.writeCsv(csv.toPath(), rows);

        String text = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        assertTrue("CSV должен начинаться с BOM", text.startsWith("\uFEFF"));

        List<String[]> parsed = PropIo.readCsv(csv.toPath());
        assertEquals(3, parsed.size());
        assertEquals("key", parsed.get(0)[0]);
        assertEquals("Он сказал \"привет\"; ок", parsed.get(2)[2]);
        assertEquals("He said \"hi\"; ok", parsed.get(2)[1]);
    }

    @Test
    public void readCsv_withoutBom() throws IOException {
        File csv = new File(temp.getRoot(), "plain.csv");
        Files.write(csv.toPath(), ("k1;Value one;Значение\nk2;Two;Два").getBytes(StandardCharsets.UTF_8));
        List<String[]> parsed = PropIo.readCsv(csv.toPath());
        assertEquals(2, parsed.size());
        assertEquals("k1", parsed.get(0)[0]);
        assertEquals("Значение", parsed.get(0)[2]);
    }
}