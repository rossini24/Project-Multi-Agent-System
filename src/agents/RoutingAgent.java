package agents;

import model.ConversationMemory;
import model.Draft;
import model.Email;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The COORDINATOR of the multi-agent organization (assignment, section 4.1).
 *
 * Organization: centralized / hierarchical. The specialized agents never talk to each
 * other: every message goes through the RoutingAgent (MEDIATOR pattern), which
 *   1. decides which agent is best suited (routing),
 *   2. gives it the information it needs (email + conversation history + feedback),
 *   3. gets the draft back and returns it to the user (the GUI or the console),
 *   4. remembers what the user actually sent (memory).
 * For the user interface it is also a FACADE: one object with a few simple methods.
 *
 * The routing is a CHAIN OF RESPONSIBILITY:
 *   link 1  EmergencyAgent (reactive rules, always first, cannot be switched off)
 *   link 2+ the routing strategies, in order (LLM classifier, then keywords...)
 *   last    fallback GenericAgent: "no agent is sufficiently suitable"
 * Each link either decides or passes the email to the next one.
 */
public class RoutingAgent {

    private static final int HISTORY_EXCHANGES = 3;

    private final EmergencyAgent emergencyAgent;
    private final List<Agent> specialists;
    private final Agent fallbackAgent;
    private final List<RoutingStrategy> strategies;
    private final ConversationMemory memory;

    public RoutingAgent(EmergencyAgent emergencyAgent, List<Agent> specialists, Agent fallbackAgent,
                        List<RoutingStrategy> strategies, ConversationMemory memory) {
        this.emergencyAgent = emergencyAgent;
        this.specialists = new CopyOnWriteArrayList<>(specialists);   // safe if the GUI adds an agent meanwhile
        this.fallbackAgent = fallbackAgent;
        this.strategies = strategies;
        this.memory = memory;
    }

    /** Routing with the configured chain of strategies. */
    public RoutingDecision route(Email email) {
        return routeWith(email, strategies);
    }

    /** Routing with a given chain of strategies (used by the experiments to compare them). */
    public RoutingDecision routeWith(Email email, List<RoutingStrategy> chain) {
        List<String> redFlags = emergencyAgent.findRedFlags(email);
        if (!redFlags.isEmpty()) {
            return new RoutingDecision(emergencyAgent, "emergency rules", "red flags: " + String.join(", ", redFlags));
        }
        for (RoutingStrategy strategy : chain) {
            RoutingDecision decision = strategy.route(email, specialists);
            if (decision != null) return decision;
        }
        return new RoutingDecision(fallbackAgent, "fallback", "no agent was clearly suitable");
    }

    /** The full minimum scenario: email -> routing -> specialized agent -> LLM -> back to the user. */
    public Draft process(Email email) {
        RoutingDecision decision = route(email);
        Draft draft = draftWith(email, decision.getAgent(), "");
        draft.setRoutingInfo(decision.toString());
        return draft;
    }

    /** Asks a given agent for a draft (also used when the reviewer changes the agent by hand). */
    public Draft draftWith(Email email, Agent agent, String feedback) {
        String history = memory.historyFor(email.getSender(), HISTORY_EXCHANGES);
        Draft draft = agent.handle(email, history, feedback);
        int known = memory.count(email.getSender());
        if (known > 0) {
            draft.addNote("Conversation memory: the last " + Math.min(known, HISTORY_EXCHANGES)
                    + " exchange(s) with this sender were given to the agent.");
        }
        return draft;
    }

    /** The reviewer rejected a draft: the agent writes a new one, taking the comments into account. */
    public Draft revise(Email email, Agent agent, Draft rejected, String comments) {
        String feedback = "YOUR PREVIOUS DRAFT (rejected by the human reviewer):\n" + rejected.getText()
                + "\n\nINSTRUCTIONS OF THE REVIEWER FOR THE NEW DRAFT:\n"
                + (comments.isBlank() ? "Write a better version." : comments);
        Draft draft = draftWith(email, agent, feedback);
        draft.setRoutingInfo(agent.getName().equals(rejected.getAgentName())
                ? rejected.getRoutingInfo()
                : agent.getName() + " - chosen by the human reviewer");
        draft.addNote("New draft written with the reviewer's feedback.");
        return draft;
    }

    /** The reviewer accepted (and maybe edited) the reply: it is "sent" and remembered. */
    public void accept(Email email, String agentName, String finalText) {
        memory.record(email, agentName, finalText);
    }

    /** All agents, the emergency one included (for the interface). */
    public List<Agent> getAllAgents() {
        List<Agent> all = new ArrayList<>(specialists);
        all.add(emergencyAgent);
        return all;
    }

    public Agent findAgent(String name) {
        for (Agent agent : getAllAgents()) {
            if (agent.getName().equals(name)) return agent;
        }
        return null;
    }

    /** Dynamic addition of an agent: from now on every strategy can choose it. */
    public void addAgent(Agent agent) {
        specialists.add(agent);
    }
}
