package rag;

import llm.LLMClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DocumentRetriever {

    private final EmbeddingClient embeddingClient;
    private final List<DocumentChunk> chunks = new ArrayList<>();

    public DocumentRetriever(EmbeddingClient embeddingClient,
                              List<String> documentPaths, List<String> documentNames) throws IOException {
        this.embeddingClient = embeddingClient;
        for (int i = 0; i < documentPaths.size(); i++) {
            for (DocumentChunk chunk : chunkDocument(documentPaths.get(i), documentNames.get(i))) {
                chunk.setEmbedding(embeddingClient.embed(chunk.getContent()));
                chunks.add(chunk);
            }
        }
    }

    private List<DocumentChunk> chunkDocument(String path, String sourceName) throws IOException {
        String fullText = Files.readString(Paths.get(path));
        String[] rawSections = fullText.split("(?m)^(?=#)"); // taglia a ogni riga che inizia con # o ##
        List<DocumentChunk> result = new ArrayList<>();
        for (String section : rawSections) {
            section = section.trim();
            if (section.isEmpty()) continue;
            int newlineIdx = section.indexOf('\n');
            String firstLine = newlineIdx == -1 ? section : section.substring(0, newlineIdx);
            String heading = firstLine.replaceFirst("^#+\\s*", "").trim();
            result.add(new DocumentChunk(sourceName, heading, section));
        }
        return result;
    }

    public List<DocumentChunk> retrieve(String query, int topK) {
        float[] queryEmbedding = embeddingClient.embed(query);
        return chunks.stream()
                .sorted(Comparator.comparingDouble(
                        (DocumentChunk c) -> cosineSimilarity(queryEmbedding, c.getEmbedding())).reversed())
                .limit(topK)
                .toList();
    }

    public String answer(String query, LLMClient llmClient, int topK) {
        List<DocumentChunk> retrieved = retrieve(query, topK);

        StringBuilder context = new StringBuilder();
        for (DocumentChunk c : retrieved) {
            context.append("[").append(c.getSourceDocument()).append(" - ").append(c.getHeading()).append("]\n");
            context.append(c.getContent()).append("\n\n");
        }

        String systemPrompt = "You are an assistant answering insurance coverage questions using ONLY the "
                + "context provided below. If the context does not contain enough information, say so "
                + "explicitly instead of guessing.\n\nContext:\n" + context;

        return llmClient.generate(systemPrompt, query);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-9);
    }
}
