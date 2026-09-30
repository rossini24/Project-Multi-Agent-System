package model;

import java.util.ArrayList;
import java.util.List;

/**
 * A reply PROPOSED by an agent. Nothing is ever sent automatically:
 * the human reviewer accepts, edits or rejects it (assignment, section 11).
 *
 * Besides the text, a draft carries "notes" for the reviewer: why the router chose
 * this agent, which context was used, and any security or privacy warning.
 * This makes the decisions of the agents explainable.
 */
public class Draft {
    private final String agentName;
    private String text = "";
    private String routingInfo = "";
    private final List<String> notes = new ArrayList<>();
    private boolean warning = false;

    public Draft(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentName() { return agentName; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getRoutingInfo() { return routingInfo; }
    public void setRoutingInfo(String routingInfo) { this.routingInfo = routingInfo; }
    public List<String> getNotes() { return notes; }
    public boolean hasWarning() { return warning; }

    /** Normal information for the reviewer. */
    public void addNote(String note) {
        notes.add(note);
    }

    /** Something the reviewer MUST look at (impersonation attempt, privacy, emergency...). */
    public void addWarning(String message) {
        notes.add("WARNING: " + message);
        warning = true;
    }
}
