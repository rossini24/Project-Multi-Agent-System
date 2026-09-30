package agents;

import model.Draft;
import model.Email;

import java.util.List;

/**
 * What EVERY agent of the system can do. The RoutingAgent and the interface
 * only know this contract, never the concrete classes: this is what lets us add
 * a new agent without changing the rest of the application (low coupling).
 *
 * In MAS terms an agent here is: an autonomous entity with its own role,
 * its own knowledge (context) and its own way of acting (handle).
 */
public interface Agent {

    /** Unique name, e.g. "PatientAgent". Also the name of its section in system_prompts.md. */
    String getName();

    /** One sentence saying which emails this agent handles. The router reads it. */
    String getDescription();

    /** Typical words of its emails, used by the keyword routing strategy. */
    List<String> getKeywords();

    /**
     * Proposes a reply to an email.
     * @param history  previous exchanges with the same sender ("" if none)
     * @param feedback comments of the reviewer on a rejected draft ("" if none)
     */
    Draft handle(Email email, String history, String feedback);
}
