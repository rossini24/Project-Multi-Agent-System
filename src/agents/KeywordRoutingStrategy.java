package agents;

import model.Email;

import java.util.ArrayList;
import java.util.List;

/**
 * Routing by KEYWORDS: each agent gets one point for each of its keywords found in the email.
 * Fast, free, deterministic and explainable, but blind to meaning
 * (it cannot understand that a doctor writing "as a patient" is a patient).
 *
 * Used as the fallback of the LLM strategy, and as a baseline in the experiments.
 * Tie between agents = low confidence, reported in the reason.
 */
public class KeywordRoutingStrategy implements RoutingStrategy {

    @Override
    public String getName() {
        return "keyword rules";
    }

    @Override
    public RoutingDecision route(Email email, List<Agent> candidates) {
        String text = (email.getSubject() + " " + email.getBody()).toLowerCase();

        Agent best = null;
        List<String> bestHits = new ArrayList<>();
        int secondScore = 0;

        for (Agent agent : candidates) {
            List<String> hits = new ArrayList<>();
            for (String keyword : agent.getKeywords()) {
                if (text.contains(keyword.toLowerCase())) hits.add(keyword);
            }
            if (hits.size() > bestHits.size()) {
                secondScore = bestHits.size();
                best = agent;
                bestHits = hits;
            } else if (hits.size() > secondScore) {
                secondScore = hits.size();
            }
        }

        if (best == null) return null;                         // no keyword at all: let the next link decide
        String reason = bestHits.size() + " keyword(s) found: " + String.join(", ", bestHits);
        if (secondScore == bestHits.size()) reason += " (tie with another agent: low confidence)";
        return new RoutingDecision(best, getName(), reason);
    }
}
