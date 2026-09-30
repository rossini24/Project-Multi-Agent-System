package rag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LMStudioEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    public LMStudioEmbeddingClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public float[] embed(String text) {
        String requestBody = "{"
                + "\"model\": \"" + model + "\","
                + "\"input\": \"" + escape(text) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractEmbedding(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Cannot compute an embedding - is the embedding model loaded in LM Studio?", e);
        }
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")          // Windows line endings (CRLF) would make the JSON invalid
                .replace("\t", "\\t")
                .replace("\n", "\\n");
    }

    private float[] extractEmbedding(String json) {
        Pattern pattern = Pattern.compile("\"embedding\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new RuntimeException("Field 'embedding' not found in the LM Studio answer: " + json);
        }
        String[] numbers = matcher.group(1).split(",");
        float[] vector = new float[numbers.length];
        for (int i = 0; i < numbers.length; i++) {
            vector[i] = Float.parseFloat(numbers[i].trim());
        }
        return vector;
    }
}
