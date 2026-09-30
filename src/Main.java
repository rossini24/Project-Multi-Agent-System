import agents.*;
import calendar.AppointmentCalendar;
import llm.LLMClient;
import llm.LMStudioClient;
import model.ConversationMemory;
import model.Draft;
import model.Email;
import model.EmailLoader;
import rag.EmbeddingClient;
import rag.LMStudioEmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Entry point. Run it from the src folder:
 *   java -cp out Main            -> graphical interface (default)
 *   java -cp out Main console    -> same workflow in the terminal
 *   java -cp out Main eval       -> experiment: routing accuracy on the 26 test emails
 *
 * createSystem() is the only place where concrete classes are created (a simple FACTORY /
 * "composition root"). Everything else only knows interfaces: to change the model,
 * the engine or an agent, this is the method to edit.
 */
public class Main {

    // ---------------- configuration ----------------
    static final String DATA = "../data/";
    static final String LM_STUDIO_URL = "http://localhost:1234";
    static final String CHAT_MODEL = "llama-3.2-3b-instruct";
    static final String EMBEDDING_MODEL = "text-embedding-nomic-embed-text-v1.5";
    static final double ROUTING_TEMPERATURE = 0.0;   // routing: always the same answer
    static final double WRITING_TEMPERATURE = 0.4;   // drafts: a little variety
    // ------------------------------------------------

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "gui";

        PromptRepository prompts = new PromptRepository(DATA + "system_prompts.md");
        LLMClient writerLlm = new LMStudioClient(LM_STUDIO_URL, CHAT_MODEL, WRITING_TEMPERATURE);
        RoutingAgent router = createSystem(prompts, writerLlm);
        List<Email> emails = EmailLoader.load(DATA + "mailbox_test_dataset.json");

        switch (mode) {
            case "console" -> runConsole(router, emails);
            case "eval" -> runEvaluation(router, prompts, emails,
                    EmailLoader.loadExpectedAgents(DATA + "mailbox_test_dataset.json"));
            default -> MailboxGui.open(router, prompts, writerLlm, emails);
        }
    }

    /** Builds the whole multi-agent organization. */
    static RoutingAgent createSystem(PromptRepository prompts, LLMClient writerLlm) throws Exception {
        LLMClient routerLlm = new LMStudioClient(LM_STUDIO_URL, CHAT_MODEL, ROUTING_TEMPERATURE);
        EmbeddingClient embedder = new LMStudioEmbeddingClient(LM_STUDIO_URL, EMBEDDING_MODEL);
        AppointmentCalendar calendar = new AppointmentCalendar(DATA + "doctors_schedule.csv", DATA + "appointments.csv");
        ConversationMemory memory = new ConversationMemory(DATA + "conversation_history.txt");

        GenericAgent generic = new GenericAgent("GenericAgent",
                "Anything else: advertising, spam, misdirected messages, practical questions not covered "
                        + "by the other agents (parking, directions, general information).",
                List.of("offer", "discount", "promotion", "newsletter", "unsubscribe", "parking",
                        "wrong contact", "wrong number", "wrong address"),
                null, prompts, writerLlm);

        List<Agent> specialists = new ArrayList<>();
        specialists.add(new PatientAgent(DATA + "context_patient_agent.md", calendar, prompts, writerLlm));
        specialists.add(new DoctorAgent(DATA + "context_doctor_agent.md", calendar, prompts, writerLlm));
        specialists.add(new InsuranceAgent(DATA + "insurance_patient_registry.md", embedder,
                List.of(DATA + "healthplus_member_guide.md", DATA + "lifesecure_member_guide.md",
                        DATA + "wellcare_member_guide.md"),
                List.of("HealthPlus", "LifeSecure", "WellCare"),
                prompts, writerLlm));
        specialists.add(new LabAgent(DATA + "context_lab_agent.md", DATA + "lab_records_metadata.md", prompts, writerLlm));
        specialists.add(generic);

        List<RoutingStrategy> strategies = List.of(
                new LlmRoutingStrategy(routerLlm, prompts),     // first: understands the meaning
                new KeywordRoutingStrategy());                  // if the LLM fails: keywords

        return new RoutingAgent(new EmergencyAgent(prompts), specialists, generic, strategies, memory);
    }

    // =====================================================================
    // Console mode: the same human-in-the-loop workflow, without the GUI
    // =====================================================================
    static void runConsole(RoutingAgent router, List<Email> emails) {
        Scanner in = new Scanner(System.in);
        for (int i = 0; i < emails.size(); i++) {
            Email email = emails.get(i);
            System.out.println("\n==================== EMAIL #" + (i + 1) + " ====================");
            System.out.println("From:    " + email.getSender());
            System.out.println("Subject: " + email.getSubject());
            System.out.println(email.getBody());
            System.out.println("\n(routing and drafting, please wait...)");

            Draft draft;
            try {
                draft = router.process(email);
            } catch (RuntimeException e) {
                System.out.println("ERROR: " + e.getMessage());
                System.out.println("Check that LM Studio is open, the server is started and the models are loaded.");
                return;
            }
            Agent agent = router.findAgent(draft.getAgentName());
            boolean done = false;
            while (!done) {
                printDraft(draft);
                System.out.print("[a]ccept  [e]dit  [r]eject with feedback  [s]kip  [q]uit > ");
                String choice = in.nextLine().trim().toLowerCase();
                switch (choice) {
                    case "a" -> {
                        router.accept(email, draft.getAgentName(), draft.getText());
                        System.out.println("-> Reply SENT (saved in the conversation history).");
                        done = true;
                    }
                    case "e" -> {
                        System.out.println("Type the new text. End with a line containing only a dot (.)");
                        StringBuilder text = new StringBuilder();
                        String line;
                        while (!(line = in.nextLine()).equals(".")) text.append(line).append("\n");
                        router.accept(email, draft.getAgentName() + " (edited)", text.toString().trim());
                        System.out.println("-> Edited reply SENT (saved in the conversation history).");
                        done = true;
                    }
                    case "r" -> {
                        System.out.print("What should the agent change? > ");
                        String comments = in.nextLine();
                        System.out.println("(writing a new draft...)");
                        try {
                            draft = router.revise(email, agent, draft, comments);
                        } catch (RuntimeException e) {
                            System.out.println("ERROR: " + e.getMessage());
                        }
                    }
                    case "s" -> done = true;
                    case "q" -> { return; }
                    default -> System.out.println("Unknown choice.");
                }
            }
        }
    }

    static void printDraft(Draft draft) {
        System.out.println("\n--- Routing: " + draft.getRoutingInfo());
        for (String note : draft.getNotes()) System.out.println("--- " + note);
        System.out.println("\n--- PROPOSED REPLY (" + draft.getAgentName() + ") ---");
        System.out.println(draft.getText());
        System.out.println("-------------------------------------------");
    }

    // =====================================================================
    // Evaluation mode: experiment on the routing quality (assignment, section 15)
    // =====================================================================
    static void runEvaluation(RoutingAgent router, PromptRepository prompts,
                              List<Email> emails, List<String> expected) {
        LLMClient routerLlm = new LMStudioClient(LM_STUDIO_URL, CHAT_MODEL, ROUTING_TEMPERATURE);
        RoutingStrategy keywords = new KeywordRoutingStrategy();
        RoutingStrategy llm = new LlmRoutingStrategy(routerLlm, prompts);

        List<List<RoutingStrategy>> configurations = List.of(
                List.of(keywords),
                List.of(llm),
                List.of(llm, keywords));
        String[] names = {"keywords only", "LLM only", "LLM + keywords"};
        int[] correct = new int[configurations.size()];

        System.out.printf("%-3s %-15s | %-26s | %-26s | %-26s%n", "#", "expected", names[0], names[1], names[2]);
        System.out.println("-".repeat(106));
        for (int i = 0; i < emails.size(); i++) {
            StringBuilder row = new StringBuilder(String.format("%-3d %-15s", i + 1, expected.get(i)));
            for (int c = 0; c < configurations.size(); c++) {
                RoutingDecision decision = router.routeWith(emails.get(i), configurations.get(c));
                String got = decision.getAgent().getName();
                boolean ok = got.equals(expected.get(i));
                if (ok) correct[c]++;
                row.append(String.format(" | %-26s", (ok ? "ok    " : "WRONG ") + got));
            }
            System.out.println(row);
        }
        System.out.println("-".repeat(106));
        for (int c = 0; c < names.length; c++) {
            System.out.printf("%-16s accuracy: %2d/%d (%.0f%%)%n", names[c], correct[c], emails.size(),
                    100.0 * correct[c] / emails.size());
        }
        System.out.println("\nNote: the emergency rules are always the first link of the chain, in every configuration.");
    }
}
