package agents;

import model.Email;

import java.util.List;

/**
 * STRATEGY pattern: one way of choosing the agent for an email.
 * The RoutingAgent does not care HOW a strategy decides; new strategies
 * (embeddings, sender history, voting between strategies...) can be added
 * without touching the RoutingAgent.
 */
public interface RoutingStrategy {

    String getName();

    /**
     * @return the chosen agent, or null if this strategy cannot decide
     *         (then the RoutingAgent asks the next strategy of the chain).
     */
    RoutingDecision route(Email email, List<Agent> candidates);
}
