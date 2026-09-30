package llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADAPTER between our LLMClient interface and the LM Studio local server
 * (OpenAI-compatible endpoint /v1/chat/completions).
 * No agent knows this class: they only know LLMClient. To use another engine
 * (Ollama, llama.cpp...) write another class that implements LLMClient.
 */
public class LMStudioClient implements LLMClient {

    private final String baseUrl;
    private final String model;
    private final double temperature; // 0 = always the same answer, higher = more creative
    private final HttpClient httpClient;

    public LMStudioClient(String baseUrl, String model) {
        this(baseUrl, model, 0.3);
    }

    public LMStudioClient(String baseUrl, String model, double temperature) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))   // fail fast if LM Studio is off
                .build();
    }

    @Override
    public String generate(String systemPrompt, String userMessage) {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))           // a small local model can be slow
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Cannot reach LM Studio at " + baseUrl
                    + " - is the local server started?", e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("LM Studio answered HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return extractContent(response.body());
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        return "{"
                + "\"model\": \"" + model + "\","
                + "\"temperature\": " + temperature + ","
                + "\"messages\": ["
                + "{\"role\": \"system\", \"content\": \"" + escape(systemPrompt) + "\"},"
                + "{\"role\": \"user\", \"content\": \"" + escape(userMessage) + "\"}"
                + "]"
                + "}";
    }

    /** Makes a Java string safe inside a JSON string (quotes, backslashes, new lines, tabs...). */
    private String escape(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }                       // Windows line endings: simply dropped
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String extractContent(String json) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescape(matcher.group(1));
        }
        throw new RuntimeException("Field 'content' not found in the LM Studio answer: " + json);
    }

    /** The opposite of escape(): turns \n, \", \\uXXXX... back into real characters. */
    private String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> { }
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(next);           // \" \\ \/
            }
        }
        return sb.toString();
    }
}
