package agents;

import llm.LLMClient;
import model.Email;

import java.util.List;

/**
 * Routing with the LOCAL LLM used as a classifier: it reads the email and the
 * one-line description of every agent, and answers with the name of one agent.
 * It understands meaning (a doctor writing as a patient, "urgent" that is not an
 * emergency...), but it is slower and can make mistakes: if its answer cannot be
 * understood, or LM Studio is unreachable, it returns null and the next strategy decides.
 *
 * Because the list of agents is built from getDescription(), a new agent is
 * immediately "known" by the router, without changing this class.
 */
public class LlmRoutingStrategy implements RoutingStrategy {

    private static final int MAX_BODY_CHARS = 1500;

    private final LLMClient llm;
    private final PromptRepository prompts;

    public LlmRoutingStrategy(LLMClient llm, PromptRepository prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public String getName() {
        return "LLM classifier";
    }

    @Override
    public RoutingDecision route(Email email, List<Agent> candidates) {
        StringBuilder agentList = new StringBuilder();
        for (Agent agent : candidates) {
            agentList.append("- ").append(agent.getName()).append(": ").append(agent.getDescription()).append("\n");
        }
        String systemPrompt = prompts.get("RoutingAgent").replace("{AGENTS}", agentList.toString().trim());

        String body = email.getBody();
        if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS) + "...";
        String userMessage = "From: " + email.getSender() + "\nSubject: " + email.getSubject() + "\nBody: " + body;

        String answer;
        try {
            answer = llm.generate(systemPrompt, userMessage);
        } catch (RuntimeException e) {
            System.err.println("LLM routing not available: " + e.getMessage());
            return null;                                     // the next strategy of the chain will decide
        }

        Agent chosen = findAgentIn(answer, candidates);
        if (chosen == null) return null;
        return new RoutingDecision(chosen, getName(), reasonIn(answer));
    }

    /** Looks first in the "AGENT:" line, then anywhere in the answer (first name that appears). */
    private Agent findAgentIn(String answer, List<Agent> candidates) {
        for (String line : answer.split("\n")) {
            String clean = line.replace(" ", "").replace("*", "").toLowerCase();
            if (clean.startsWith("agent:")) {
                for (Agent agent : candidates) {
                    String name = agent.getName().toLowerCase();
                    String shortName = name.replace("agent", "");         // "PatientAgent" -> "patient"
                    if (clean.contains(name) || clean.substring(6).startsWith(shortName)) return agent;
                }
            }
        }
        String all = answer.replace(" ", "").toLowerCase();
        Agent first = null;
        int firstPosition = Integer.MAX_VALUE;
        for (Agent agent : candidates) {
            int position = all.indexOf(agent.getName().toLowerCase());
            if (position >= 0 && position < firstPosition) {
                firstPosition = position;
                first = agent;
            }
        }
        return first;
    }

    private String reasonIn(String answer) {
        for (String line : answer.split("\n")) {
            String t = line.replace("*", "").trim();
            if (t.toUpperCase().startsWith("REASON:")) return t.substring(7).trim();
        }
        return "no reason given by the model";
    }
}
