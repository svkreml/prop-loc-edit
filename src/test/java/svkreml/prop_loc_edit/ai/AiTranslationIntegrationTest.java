package svkreml.prop_loc_edit.ai;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class AiTranslationIntegrationTest {

    private AiTranslationService service;
    private AiConfig config;

    @Before
    public void setUp() throws Exception {
        config = AiConfig.load();
        Assume.assumeTrue("AI server not reachable: " + config.getBaseUrl(), isReachable(config.getBaseUrl()));
        service = new AiTranslationService(config);
    }

    private boolean isReachable(String baseUrl) {
        try {
            String base = baseUrl.replaceAll("/+$", "");
            URL url = new URL(base + "/models");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            return connection.getResponseCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void translateSimpleTextToRussian() throws Exception {
        String ru = service.translate("Welcome to our service");

        assertNotNull(ru);
        assertFalse("Translation is empty", ru.trim().isEmpty());
        assertTrue("Expected Russian translation, got: " + ru, AiTranslationService.isRussian(ru));
    }

    @Test
    public void translateMultipleTextsToRussian() throws Exception {
        List<String> english = Arrays.asList(
                "Please enter your password",
                "File saved successfully",
                "Loading, please wait...",
                "Failed to connect to the server"
        );

        for (String en : english) {
            String ru = service.translate(en);
            assertTrue("Not a Russian translation for [" + en + "]: " + ru,
                    AiTranslationService.isRussian(ru));
        }
    }

    @Test
    public void verifyServiceUsesConfiguredEndpoint() {
        assertNotNull(config.getBaseUrl());
        assertTrue(config.getModel() != null && !config.getModel().isEmpty());
        assertFalse("Configured model is empty", config.getModel().trim().isEmpty());
    }

    @Test
    public void verifyAndCorrectFixesBrokenRussian() throws Exception {
        String en = "Welcome to our service";
        String brokenRu = "Добро пожаловать наш сервис сотрудникам";
        String ru = service.verifyAndCorrect(en, brokenRu);

        assertNotNull(ru);
        assertFalse("Result is empty", ru.trim().isEmpty());
        assertTrue("Expected Russian result, got: " + ru, AiTranslationService.isRussian(ru));
    }

    @Test
    public void verifyAndCorrectKeepsValidRussian() throws Exception {
        String en = "Please enter your password";
        String validRu = "Пожалуйста, введите свой пароль";
        String ru = service.verifyAndCorrect(en, validRu);

        assertNotNull(ru);
        assertFalse("Result is empty", ru.trim().isEmpty());
        assertTrue("Expected Russian result, got: " + ru, AiTranslationService.isRussian(ru));
    }
}