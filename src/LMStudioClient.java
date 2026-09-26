import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LMStudioClient implements LLMClient {

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    public LMStudioClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String generate(String systemPrompt, String userMessage) {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractContent(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Errore nella chiamata all'LLM locale", e);
        }
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        return "{"
                + "\"model\": \"" + model + "\","
                + "\"messages\": ["
                + "{\"role\": \"system\", \"content\": \"" + escape(systemPrompt) + "\"},"
                + "{\"role\": \"user\", \"content\": \"" + escape(userMessage) + "\"}"
                + "]"
                + "}";
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private String extractContent(String json) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String raw = matcher.group(1);
            return raw.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        throw new RuntimeException("Campo 'content' non trovato nella risposta: " + json);
    }
}