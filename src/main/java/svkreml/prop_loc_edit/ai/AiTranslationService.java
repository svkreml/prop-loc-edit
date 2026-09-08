package svkreml.prop_loc_edit.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiTranslationService {

    private static final Logger LOG = Logger.getLogger(AiTranslationService.class.getName());

    public static final double RUSSIAN_RATIO_THRESHOLD = 0.35;
    private static final int CACHE_MAX_SIZE = 10000;
    private static final Pattern BRACE_PLACEHOLDER = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern PRINTF_PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final Pattern USAGE_PROMPT = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern USAGE_COMPLETION = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    private final String baseUrl;
    private final String model;
    private final String fallbackBaseUrl;
    private final String fallbackModel;
    private final String apiKey;
    private final int timeoutMs;
    private final double temperature;
    private final int maxTokens;
    private final int maxAttempts;
    private final int threads;
    private final String context;

    private final AtomicLong totalTokens = new AtomicLong();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public AiTranslationService() {
        this(AiConfig.load());
    }

    public AiTranslationService(AiConfig config) {
        this.baseUrl = config.getBaseUrl();
        this.model = config.getModel();
        this.fallbackBaseUrl = config.getFallbackBaseUrl() == null ? "" : config.getFallbackBaseUrl().trim();
        this.fallbackModel = config.getFallbackModel() == null ? "" : config.getFallbackModel().trim();
        this.apiKey = config.getApiKey();
        this.timeoutMs = config.getTimeoutMs();
        this.temperature = config.getTemperature();
        this.maxTokens = config.getMaxTokens();
        this.maxAttempts = config.getMaxAttempts() > 0 ? config.getMaxAttempts() : 3;
        this.threads = Math.max(1, config.getThreads());
        this.context = config.getContext() == null ? "" : config.getContext().trim();
    }

    public int getThreads() {
        return threads;
    }

    public long getTotalTokens() {
        return totalTokens.get();
    }

    public void resetTokens() {
        totalTokens.set(0);
    }

    public String translate(String englishText) throws Exception {
        if (englishText == null || englishText.trim().isEmpty()) {
            return "";
        }
        return translateInternal(englishText, null);
    }

    public String verifyAndCorrect(String englishText, String existingRussian) throws Exception {
        if (englishText == null || englishText.trim().isEmpty()) {
            return "";
        }
        return translateInternal(englishText,
                (existingRussian == null || existingRussian.trim().isEmpty()) ? null : existingRussian);
    }

    private String translateInternal(String englishText, String existingRussian) throws Exception {
        String key = cacheKey(englishText, existingRussian);
        String cached = cache.get(key);
        if (cached != null) {
            LOG.fine("AI: cache hit (text length=" + englishText.length() + ", result length=" + cached.length() + ")");
            return cached;
        }

        try {
            String result = attemptWithEndpoint(baseUrl, model, englishText, existingRussian);
            putCache(key, result);
            return result;
        } catch (Exception primary) {
            if (fallbackBaseUrl.isEmpty()) {
                throw primary;
            }
            LOG.warning("Primary AI server failed, trying fallback: " + primary.getMessage());
            String fallback = fallbackModel.isEmpty() ? model : fallbackModel;
            try {
                String result = attemptWithEndpoint(fallbackBaseUrl, fallback, englishText, existingRussian);
                putCache(key, result);
                return result;
            } catch (Exception secondary) {
                primary.addSuppressed(secondary);
                LOG.severe("Fallback AI server also failed: " + secondary.getMessage());
                throw primary;
            }
        }
    }

    private String attemptWithEndpoint(String url, String modelName, String englishText, String existingRussian)
            throws Exception {
        String lastRaw = null;
        String lastErrorMsg = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LOG.fine("AI: попытка " + attempt + "/" + maxAttempts + " -> " + url + " (модель: " + modelName + ", вход: "
                        + trimTo(englishText, 120) + ")");
                long startMs = System.currentTimeMillis();
                String raw = callApi(url, modelName, englishText, existingRussian);
                long ms = System.currentTimeMillis() - startMs;
                lastRaw = raw;
                String cleaned = cleanup(raw);
                if (isRussian(cleaned) && hasSamePlaceholders(englishText, cleaned)) {
                    LOG.fine("AI: OK за " + ms + " мс, результат: " + trimTo(cleaned, 120));
                    return cleaned;
                }
                lastErrorMsg = "result rejected (language or placeholders): " + cleaned;
                LOG.warning("AI: результат отклонён (язык/плейсхолдеры) после " + ms + " мс: " + trimTo(cleaned, 200));
            } catch (Exception e) {
                lastErrorMsg = e.getMessage();
                LOG.warning("AI call failed (attempt " + attempt + "/" + maxAttempts + "): " + e.getMessage());
            }
        }

        String detail = lastErrorMsg != null ? " Last error: " + lastErrorMsg : "";
        if (lastRaw != null) {
            detail += " Raw response: " + trimTo(lastRaw, 400);
        }
        throw new TranslationVerificationException(
                "AI translation failed verification after " + maxAttempts + " attempt(s)." + detail);
    }

    private static String cacheKey(String englishText, String existingRussian) {
        return englishText + "\u0000" + (existingRussian == null ? "" : existingRussian);
    }

    private void putCache(String key, String value) {
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
        cache.put(key, value);
    }

    private String callApi(String endpointBase, String modelName, String englishText, String existingRussian)
            throws Exception {
        long startMs = System.currentTimeMillis();
        String endpoint = endpointBase;
        if (!endpoint.endsWith("/")) {
            endpoint += "/";
        }
        if (!endpoint.toLowerCase().contains("/chat/completions")) {
            endpoint += "chat/completions";
        }

        String systemPrompt = buildSystemPrompt(existingRussian != null, context);
        String userContent;
        if (existingRussian == null) {
            userContent = englishText;
        } else {
            userContent = "English original:\n" + englishText + "\n\nCurrent Russian translation:\n" + existingRussian;
        }

        String json = "{\"model\":\"" + escapeJson(modelName) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userContent) + "\"}"
                + "],"
                + "\"temperature\":" + temperature + ","
                + "\"max_tokens\":" + maxTokens + ","
                + "\"thinking\":false"
                + "}";

        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        connection.setDoOutput(true);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int responseCode = connection.getResponseCode();

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        if (responseCode >= 400) {
            throw new Exception("API error (" + responseCode + "): " + trimTo(response.toString(), 500));
        }

        String raw = response.toString();
        String content = extractContent(raw);
        long tokens = countTokens(raw);
        long ms = System.currentTimeMillis() - startMs;
        LOG.fine("AI: " + endpoint + " (модель: " + modelName + ") -> HTTP " + responseCode
                + " за " + ms + " мс, токенов: " + tokens + ", результат: " + trimTo(content, 120));
        return content;
    }

    private long countTokens(String json) {
        try {
            long sum = 0;
            Matcher m = USAGE_PROMPT.matcher(json);
            if (m.find()) {
                sum += Long.parseLong(m.group(1));
            }
            m = USAGE_COMPLETION.matcher(json);
            if (m.find()) {
                sum += Long.parseLong(m.group(1));
            }
            if (sum > 0) {
                totalTokens.addAndGet(sum);
            }
            return sum;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public String checkConnection() throws Exception {
        String endpoint = baseUrl;
        if (!endpoint.endsWith("/")) {
            endpoint += "/";
        }
        if (!endpoint.toLowerCase().contains("/models")) {
            endpoint += "models";
        }

        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);

        int responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            throw new Exception("API error (" + responseCode + "): " + trimTo(response.toString(), 300));
        }
        return "Соединение с ИИ установлено.\nАдрес: " + baseUrl + "\nМодель: " + model;
    }

    static boolean hasSamePlaceholders(String english, String russian) {
        if (english == null) {
            return true;
        }
        Set<String> en = placeholders(english);
        if (en.isEmpty()) {
            return true;
        }
        return placeholders(russian).containsAll(en);
    }

    static Set<String> placeholders(String text) {
        Set<String> set = new LinkedHashSet<>();
        if (text == null) {
            return set;
        }
        Matcher m = BRACE_PLACEHOLDER.matcher(text);
        while (m.find()) {
            set.add(m.group());
        }
        m = PRINTF_PLACEHOLDER.matcher(text);
        while (m.find()) {
            set.add(m.group());
        }
        return set;
    }

    static String buildSystemPrompt(boolean verifyExisting, String context) {
        String base;
        if (verifyExisting) {
            base = "You are a professional translator and proofreader. You are given an English text and its "
                    + "current Russian translation. Verify that the Russian translation is correct, complete and natural. "
                    + "If it contains errors, provide the corrected Russian translation. If it is already correct, "
                    + "return it unchanged. Return ONLY the final Russian text, without explanations, quotes or extra text. "
                    + "Preserve placeholders such as {name}, {0}, %s, %d, %1$s exactly as they are.";
        } else {
            base = "You are a professional translator. Translate the given text from English to Russian. "
                    + "Return only the translation in Russian, without explanations, quotes or extra text. "
                    + "Preserve proper nouns and technical terms in their original spelling where appropriate. "
                    + "Preserve placeholders such as {name}, {0}, %s, %d, %1$s exactly as they are.";
        }
        if (context != null && !context.trim().isEmpty()) {
            base += " Context description of what is being localized:\n"
                    + context.trim()
                    + "\nUse terminology and style appropriate for this context.";
        }
        return base;
    }

    static String extractContent(String jsonResponse) {
        String marker = "\"content\":\"";
        int idx = jsonResponse.indexOf(marker);
        if (idx < 0) {
            throw new RuntimeException("Failed to parse API response: " + trimTo(jsonResponse, 300));
        }
        int start = idx + marker.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < jsonResponse.length(); i++) {
            char c = jsonResponse.charAt(i);
            if (c == '\\' && i + 1 < jsonResponse.length()) {
                char next = jsonResponse.charAt(i + 1);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        i++;
                        break;
                    case 'r':
                        sb.append('\r');
                        i++;
                        break;
                    case 't':
                        sb.append('\t');
                        i++;
                        break;
                    case '"':
                        sb.append('"');
                        i++;
                        break;
                    case '\\':
                        sb.append('\\');
                        i++;
                        break;
                    case 'u':
                        if (i + 5 < jsonResponse.length()) {
                            try {
                                sb.append((char) Integer.parseInt(jsonResponse.substring(i + 2, i + 6), 16));
                                i += 5;
                            } catch (NumberFormatException ignored) {
                                sb.append('\\');
                            }
                        } else {
                            sb.append('\\');
                        }
                        break;
                    default:
                        sb.append(next);
                        i++;
                        break;
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String cleanup(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();

        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            } else {
                s = s.substring(3);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }

        if (s.startsWith("`") && s.endsWith("`") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }

        while (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')
                    || (a == '\u00AB' && b == '\u00BB')
                    || (a == '\u201C' && b == '\u201D')
                    || (a == '\u201E' && b == '\u201D')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }

        String lower = s.toLowerCase();
        String[] prefixes = {
                "перевод:", "перевод —", "перевод на русский:", "по-русски:",
                "translation:", "перевод"
        };
        for (String p : prefixes) {
            if (lower.startsWith(p)) {
                s = s.substring(p.length()).trim();
                break;
            }
        }

        if (s.startsWith("- ") || s.startsWith("• ") || s.startsWith("* ")) {
            s = s.substring(2).trim();
        }

        return s;
    }

    static boolean isRussian(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        int letters = 0;
        int cyrillic = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCyrillic(c)) {
                cyrillic++;
                letters++;
            } else if (isLatin(c)) {
                letters++;
            }
        }
        if (letters == 0) {
            return false;
        }
        return (double) cyrillic / letters >= RUSSIAN_RATIO_THRESHOLD;
    }

    private static boolean isCyrillic(char c) {
        return c >= '\u0400' && c <= '\u04FF';
    }

    private static boolean isLatin(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '\u00C0' && c <= '\u00FF');
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
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
        return sb.toString();
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}