package agents;

/**
 * The result of routing: WHICH agent, decided by WHOM, and WHY.
 * Keeping the reason makes the routing explainable to the human reviewer.
 */
public class RoutingDecision {
    private final Agent agent;
    private final String decidedBy;
    private final String reason;

    public RoutingDecision(Agent agent, String decidedBy, String reason) {
        this.agent = agent;
        this.decidedBy = decidedBy;
        this.reason = reason;
    }

    public Agent getAgent() { return agent; }
    public String getDecidedBy() { return decidedBy; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return agent.getName() + " - chosen by " + decidedBy + ": " + reason;
    }
}
