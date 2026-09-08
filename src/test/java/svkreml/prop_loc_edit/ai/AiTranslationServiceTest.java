package svkreml.prop_loc_edit.ai;

import org.junit.Test;

import static org.junit.Assert.*;

public class AiTranslationServiceTest {

    @Test
    public void isRussian_acceptsRussianText() {
        assertTrue(AiTranslationService.isRussian("Добро пожаловать в наш сервис"));
        assertTrue(AiTranslationService.isRussian("Привет!"));
        assertTrue(AiTranslationService.isRussian("Загрузить файл NDA.docx"));
        assertTrue(AiTranslationService.isRussian("Пароль должен содержать не менее 8 символов"));
    }

    @Test
    public void isRussian_rejectsEnglishText() {
        assertFalse(AiTranslationService.isRussian("Welcome to our service"));
        assertFalse(AiTranslationService.isRussian("Please enter your password"));
        assertFalse(AiTranslationService.isRussian("Loading, please wait..."));
    }

    @Test
    public void isRussian_rejectsNumbersAndEmpty() {
        assertFalse(AiTranslationService.isRussian(""));
        assertFalse(AiTranslationService.isRussian("   "));
        assertFalse(AiTranslationService.isRussian("123 456"));
        assertFalse(AiTranslationService.isRussian(null));
    }

    @Test
    public void cleanup_removesMarkdownFences() {
        assertEquals("Добро пожаловать", AiTranslationService.cleanup("```\nДобро пожаловать\n```"));
        assertEquals("Привет мир", AiTranslationService.cleanup("```text\nПривет мир\n```"));
    }

    @Test
    public void cleanup_removesSurroundingQuotes() {
        assertEquals("Добро пожаловать", AiTranslationService.cleanup("\"Добро пожаловать\""));
        assertEquals("Добро пожаловать", AiTranslationService.cleanup("«Добро пожаловать»"));
        assertEquals("Добро", AiTranslationService.cleanup("`Добро`"));
    }

    @Test
    public void cleanup_removesPrefixAndPunct() {
        assertEquals("Добро пожаловать", AiTranslationService.cleanup("Перевод: Добро пожаловать"));
        assertEquals("Добро пожаловать", AiTranslationService.cleanup("- Добро пожаловать"));
    }

    @Test
    public void extractContent_parsesRealisticResponseForReasoningModels() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"reasoning_content\":\"The user wants a translation...\","
                + "\"content\":\"Добро пожаловать в наш сервис\"}}]}";
        assertEquals("Добро пожаловать в наш сервис", AiTranslationService.extractContent(json));
    }

    @Test
    public void extractContent_handlesEscapes() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"Строка \\\"в кавычках\\\" и \\n новая\"}}]}";
        assertEquals("Строка \"в кавычках\" и \n новая", AiTranslationService.extractContent(json));
    }

    @Test
    public void extractContent_returnsEmptyForAbsentText() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        assertEquals("", AiTranslationService.extractContent(json));
    }

    @Test
    public void extractContent_throwsOnMalformed() {
        try {
            AiTranslationService.extractContent("{\"error\":\"oops\"}");
            fail("Expected RuntimeException");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("Failed to parse"));
        }
    }

    @Test
    public void hasSamePlaceholders_acceptsPreservedBraceAndPrintf() {
        assertTrue(AiTranslationService.hasSamePlaceholders(
                "Hello {name}, you have {count} files",
                "Привет {name}, у вас {count} файлов"));
        assertTrue(AiTranslationService.hasSamePlaceholders(
                "You have %d messages from %s",
                "У вас %d сообщений от %s"));
        assertTrue(AiTranslationService.hasSamePlaceholders(
                "Balance: %1$s",
                "Баланс: %1$s"));
    }

    @Test
    public void hasSamePlaceholders_rejectsMissingPlaceholder() {
        assertFalse(AiTranslationService.hasSamePlaceholders(
                "Hello {name}, you have %d messages",
                "Привет, у вас сообщения"));
        assertFalse(AiTranslationService.hasSamePlaceholders(
                "Error {code}: %s",
                "Ошибка %s"));
    }

    @Test
    public void hasSamePlaceholders_acceptsTextWithoutPlaceholders() {
        assertTrue(AiTranslationService.hasSamePlaceholders("Welcome", "Добро пожаловать"));
        assertTrue(AiTranslationService.hasSamePlaceholders(null, "Что угодно"));
        assertTrue(AiTranslationService.hasSamePlaceholders("", ""));
    }

    @Test
    public void placeholders_extractsBraceAndPrintf() {
        java.util.Set<String> expected = new java.util.LinkedHashSet<>();
        expected.add("{name}");
        expected.add("%d");
        expected.add("%s");
        assertEquals(expected, AiTranslationService.placeholders("Привет {name}, у вас %d сообщений от %s"));
    }

    @Test
    public void buildSystemPrompt_includesContextWhenPresent() {
        String prompt = AiTranslationService.buildSystemPrompt(false,
                "Локализация Keycloak. Термин login — 'вход'.");
        assertTrue(prompt.contains("Translate the given text"));
        assertTrue(prompt.contains("Локализация Keycloak"));
        assertTrue(prompt.contains("login"));
    }

    @Test
    public void buildSystemPrompt_omitsContextWhenEmpty() {
        String prompt = AiTranslationService.buildSystemPrompt(false, null);
        assertTrue(prompt.contains("Translate the given text"));
        assertFalse(prompt.contains("Context description"));
    }

    @Test
    public void buildSystemPrompt_verifyModeIsProofread() {
        String prompt = AiTranslationService.buildSystemPrompt(true, "");
        assertTrue(prompt.contains("proofreader"));
        assertTrue(prompt.contains("return it unchanged"));
    }
}