package svkreml.prop_loc_edit.ai;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AiConfig {

    private static final String DEFAULT_PATH = "ai-config.json";

    private String baseUrl = "http://localhost:8080/v1";
    private String model = "llama3.2";
    private String fallbackBaseUrl = "";
    private String fallbackModel = "";
    private String apiKey = "";
    private int timeoutMs = 120000;
    private double temperature = 0.2;
    private int maxTokens = 2048;
    private int maxAttempts = 3;
    private int threads = 2;
    private String context = "";

    public static Path defaultPath() {
        return Paths.get(DEFAULT_PATH);
    }

    public static boolean exists() {
        return Files.exists(defaultPath());
    }

    public static AiConfig load() {
        return load(defaultPath());
    }

    public static AiConfig load(Path path) {
        AiConfig config = new AiConfig();
        if (!Files.exists(path)) {
            return config;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) > 0) {
                sb.append(buf, 0, read);
            }
            parseJson(sb.toString(), config);
        } catch (IOException e) {
            System.err.println("Failed to load " + path + ", using defaults: " + e.getMessage());
        }
        return config;
    }

    public void save() throws IOException {
        saveTo(defaultPath());
    }

    public void saveTo(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"baseUrl\": ").append(toJsonString(baseUrl)).append(",\n");
        sb.append("  \"model\": ").append(toJsonString(model)).append(",\n");
        sb.append("  \"fallbackBaseUrl\": ").append(toJsonString(fallbackBaseUrl)).append(",\n");
        sb.append("  \"fallbackModel\": ").append(toJsonString(fallbackModel)).append(",\n");
        sb.append("  \"apiKey\": ").append(toJsonString(apiKey)).append(",\n");
        sb.append("  \"timeoutMs\": ").append(timeoutMs).append(",\n");
        sb.append("  \"temperature\": ").append(temperature).append(",\n");
        sb.append("  \"maxTokens\": ").append(maxTokens).append(",\n");
        sb.append("  \"maxAttempts\": ").append(maxAttempts).append(",\n");
        sb.append("  \"threads\": ").append(threads).append(",\n");
        sb.append("  \"context\": ").append(toJsonString(context)).append("\n");
        sb.append("}\n");
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String toJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
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
        return sb.append('"').toString();
    }

    private static void parseJson(String json, AiConfig config) {
        config.baseUrl = getString(json, "baseUrl", config.baseUrl);
        config.model = getString(json, "model", config.model);
        config.fallbackBaseUrl = getString(json, "fallbackBaseUrl", config.fallbackBaseUrl);
        config.fallbackModel = getString(json, "fallbackModel", config.fallbackModel);
        config.apiKey = getString(json, "apiKey", config.apiKey);
        config.timeoutMs = getInt(json, "timeoutMs", config.timeoutMs);
        config.temperature = getDouble(json, "temperature", config.temperature);
        config.maxTokens = getInt(json, "maxTokens", config.maxTokens);
        config.maxAttempts = getInt(json, "maxAttempts", config.maxAttempts);
        config.threads = getInt(json, "threads", config.threads);
        config.context = getString(json, "context", config.context);
    }

    private static String getString(String json, String key, String def) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return def;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t' || json.charAt(start) == '\n' || json.charAt(start) == '\r')) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return def;
        }
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                sb.append(json.charAt(i));
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return def;
    }

    private static int getInt(String json, String key, int def) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return def;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t' || json.charAt(start) == '\n' || json.charAt(start) == '\r')) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return def;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double getDouble(String json, String key, double def) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return def;
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return def;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t' || json.charAt(start) == '\n' || json.charAt(start) == '\r')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        if (end == start) {
            return def;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getFallbackBaseUrl() {
        return fallbackBaseUrl;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getContext() {
        return context;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setFallbackBaseUrl(String fallbackBaseUrl) {
        this.fallbackBaseUrl = fallbackBaseUrl;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }
}