package agents;

import model.Draft;
import model.Email;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A REACTIVE agent (course, chapter 1: "perception -> action", simple condition-action rules),
 * in a system where all the other agents are cognitive (they reason with the LLM).
 * This makes the MAS HETEROGENEOUS.
 *
 * Why no LLM here: in an emergency, a slow and non-deterministic answer is a risk.
 * The rules are fixed red-flag patterns (taken from the "Situations requiring immediate
 * attention" section of the practice policies), and the reply is a fixed template.
 *
 * It is the FIRST link of the routing CHAIN OF RESPONSIBILITY: RoutingAgent asks it
 * first "is this an emergency?", and only if the answer is no the email goes to the
 * normal routing strategies. Words like "urgent" alone are NOT red flags on purpose
 * (see test email #7): being in a hurry is not a medical emergency.
 */
public class EmergencyAgent implements Agent {

    public static final String NAME = "EmergencyAgent";

    /** label shown to the reviewer -> pattern searched in the lower-case text */
    private static final Map<String, Pattern> RED_FLAGS = new LinkedHashMap<>();
    static {
        RED_FLAGS.put("chest pain", Pattern.compile("chest (pain|pains|tightness|pressure)|pain in (my|the) chest"));
        RED_FLAGS.put("breathing difficulty", Pattern.compile(
                "(can't|cannot|can not|unable to|struggling to|difficulty|trouble|hard to) breath|short(ness)? of breath"));
        RED_FLAGS.put("throat closing or swelling", Pattern.compile("throat[^.!?]{0,30}(closing|swell)"));
        RED_FLAGS.put("swelling of face, lips or tongue", Pattern.compile(
                "(face|lips|tongue)[^.!?]{0,30}swell|swell[^.!?]{0,30}(face|lips|tongue)"));
        RED_FLAGS.put("loss of consciousness", Pattern.compile("passed out|fainted|unconscious|loss of consciousness"));
        RED_FLAGS.put("severe bleeding", Pattern.compile(
                "(heavy|heavily|uncontrollable|severe) bleed|bleeding (heavily|a lot|won't stop)"));
        RED_FLAGS.put("stroke, seizure or heart attack", Pattern.compile("\\b(stroke|seizure|heart attack)\\b"));
        RED_FLAGS.put("suicidal thoughts", Pattern.compile("suicid|kill myself|end my life"));
    }

    private final PromptRepository prompts;

    public EmergencyAgent(PromptRepository prompts) {
        this.prompts = prompts;
    }

    @Override public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "Real medical emergencies (chest pain, breathing difficulty, severe allergic reaction...). "
                + "Reactive agent: fixed rules and fixed reply, no LLM.";
    }

    @Override
    public List<String> getKeywords() {
        return new ArrayList<>(RED_FLAGS.keySet());
    }

    /** The perception: which red flags are present in the email? (empty list = no emergency) */
    public List<String> findRedFlags(Email email) {
        String text = (email.getSubject() + " " + email.getBody()).toLowerCase().replace('\u2019', '\'');
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> flag : RED_FLAGS.entrySet()) {
            if (flag.getValue().matcher(text).find()) found.add(flag.getKey());
        }
        return found;
    }

    /** The action: an immediate, fixed reply + an alert for the staff. History and feedback are ignored. */
    @Override
    public Draft handle(Email email, String history, String feedback) {
        Draft draft = new Draft(NAME);
        draft.setText(prompts.get(NAME));
        draft.addWarning("URGENT - red flags detected: " + String.join(", ", findRedFlags(email))
                + ". Send this reply NOW and alert a doctor / call the sender.");
        draft.addNote("No LLM used: fixed reply template (reactive agent). The template can be edited in system_prompts.md.");
        return draft;
    }
}
