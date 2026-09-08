package svkreml.prop_loc_edit.ai;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class AiConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void saveAndLoad_roundTripPreservesAllFields() throws Exception {
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://example.com:9931/v1");
        config.setModel("test-model.gguf");
        config.setApiKey("secret key");
        config.setTimeoutMs(30000);
        config.setTemperature(0.7);
        config.setMaxTokens(1024);
        config.setMaxAttempts(5);
        config.setThreads(4);
        config.setContext("Локализация Keycloak");

        Path path = temp.newFile("ai-config.json").toPath();
        config.saveTo(path);

        AiConfig loaded = AiConfig.load(path);
        assertEquals("http://example.com:9931/v1", loaded.getBaseUrl());
        assertEquals("test-model.gguf", loaded.getModel());
        assertEquals("secret key", loaded.getApiKey());
        assertEquals(30000, loaded.getTimeoutMs());
        assertEquals(0.7, loaded.getTemperature(), 0.0001);
        assertEquals(1024, loaded.getMaxTokens());
        assertEquals(5, loaded.getMaxAttempts());
        assertEquals(4, loaded.getThreads());
        assertEquals("Локализация Keycloak", loaded.getContext());
    }

    @Test
    public void load_returnsDefaultsForMissingFile() {
        Path missing = temp.getRoot().toPath().resolve("nope.json");
        AiConfig loaded = AiConfig.load(missing);
        assertEquals("http://localhost:8080/v1", loaded.getBaseUrl());
        assertEquals("llama3.2", loaded.getModel());
        assertEquals(3, loaded.getMaxAttempts());
    }
}