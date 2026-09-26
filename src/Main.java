public class Main {
    public static void main(String[] args) {
        LLMClient client = new LMStudioClient("http://localhost:1234", "llama-3.2-3b-instruct");

        String risposta = client.generate(
            "Sei un assistente che risponde sempre in italiano, in una sola frase.",
            "Ciao, come stai?"
        );

        System.out.println("Risposta del modello: " + risposta);
    }
}