public interface LLMClient {
    String generate(String systemPrompt, String userMessage);
}