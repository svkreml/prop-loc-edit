package svkreml.prop_loc_edit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OrderedPropertiesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void order_reproduced_from_en_file_and_kept_on_save() throws Exception {
        File en = new File(temp.getRoot(), "messages_en.properties");
        Files.write(en.toPath(), (
                "b = B value\n" +
                "a.client.login = Login\n" +
                "z.enable = Enable\n"
        ).getBytes(StandardCharsets.UTF_8));

        OrderedProperties props = new OrderedProperties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(en.toPath()), StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        List<String> order = new ArrayList<>();
        props.forEach((k, v) -> order.add(k.toString()));
        assertEquals(List.of("b", "a.client.login", "z.enable"), order);

        props.setProperty("new.key", "New value");
        props.keySet().removeIf(k -> "z.enable".equals(k.toString()));

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(en), StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }

        String text = new String(Files.readAllBytes(en.toPath()), StandardCharsets.UTF_8);
        assertFalse("удалённый ключ не должен попасть в файл", text.contains("z.enable"));

        OrderedProperties reloaded = new OrderedProperties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(en.toPath()), StandardCharsets.UTF_8)) {
            reloaded.load(reader);
        }
        List<String> orderAfter = new ArrayList<>();
        reloaded.forEach((k, v) -> orderAfter.add(k.toString()));
        assertEquals("порядок из EN файла должен сохраняться", List.of("b", "a.client.login", "new.key"), orderAfter);
        assertEquals("New value", reloaded.getProperty("new.key"));
        assertNull(reloaded.getProperty("z.enable"));
    }

    @Test
    public void multiline_value_round_trips() throws Exception {
        File en = new File(temp.getRoot(), "multi.properties");
        OrderedProperties props = new OrderedProperties();
        props.setProperty("unmanagedAttributesHelpText",
                "Unmanaged attributes are user attributes not explicitly defined in the user profile configuration.\n"
                + "By default, unmanaged attributes are created as read-only for all users.\n"
                + "Only administrators can view them.");
        props.setProperty("plain", "one\ttwo");
        props.setProperty("leading.space", " starts with space");

        write(en.toPath(), props);

        OrderedProperties reloaded = new OrderedProperties();
        read(en.toPath(), reloaded);

        assertEquals("Unmanaged attributes are user attributes not explicitly defined in the user profile configuration.\n"
                + "By default, unmanaged attributes are created as read-only for all users.\n"
                + "Only administrators can view them.",
                reloaded.getProperty("unmanagedAttributesHelpText"));
        assertEquals("one\ttwo", reloaded.getProperty("plain"));
        assertEquals(" starts with space", reloaded.getProperty("leading.space"));

        String text = new String(Files.readAllBytes(en.toPath()), StandardCharsets.UTF_8);
        assertTrue("translate-сроки должны писаться эскейпами \\n, а не физическим переносом с \\",
                text.contains("\\n"));
    }

    @Test
    public void allEscapedKinds_read_then_save_roundTrip() throws Exception {
        File en = new File(temp.getRoot(), "kinds.properties");
        Files.write(en.toPath(), (
                "# comment line\n" +
                "requestObject.request\\ or\\ request_uri=Request or Request URI\n" +
                "multi=first part \\\n" +
                "  second part \\\n" +
                "  third part\n" +
                "path=C:\\\\Program Files\\\\here\n" +
                "escapes=line\\\\ n literal \\\\ backslash \\\\t tab\n" +
                "real.newline=line A \\n line B\n" +
                "unicode=\\u041f\\u043e\\u0440\\u044f\\u0434\\u043e\\u043a\n" +
                "leading.space=\\ value\n"
        ).getBytes(StandardCharsets.UTF_8));

        OrderedProperties first = new OrderedProperties();
        read(en.toPath(), first);

        assertEquals("first part second part third part", first.getProperty("multi"));
        assertEquals("Порядок", first.getProperty("unicode"));
        assertEquals(" value", first.getProperty("leading.space"));
        assertEquals("C:\\Program Files\\here", first.getProperty("path"));

        Path out = new File(temp.getRoot(), "kinds_out.properties").toPath();
        write(out, first);

        OrderedProperties second = new OrderedProperties();
        read(out, second);

        List<String> firstKeys = new ArrayList<>();
        first.forEach((k, v) -> firstKeys.add(k.toString()));
        List<String> secondKeys = new ArrayList<>();
        second.forEach((k, v) -> secondKeys.add(k.toString()));
        assertEquals("порядок ключей должен сохраняться", firstKeys, secondKeys);

        for (String key : firstKeys) {
            assertEquals("значения всех типов должны пережить read -> save -> read", 
                    first.getProperty(key), second.getProperty(key));
        }

        String text = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue("ключ с пробелами должен писаться с \\ ", text.contains("\\ or\\"));
        assertTrue("перенос в значении должен писаться \\n", text.contains("\\n"));
        assertTrue("табуляция должна писаться \\t", text.contains("\\t"));
    }

    private void write(Path path, OrderedProperties props) throws Exception {
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(path.toFile()), StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }
    }

    private void read(Path path, OrderedProperties props) throws Exception {
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
    }
}