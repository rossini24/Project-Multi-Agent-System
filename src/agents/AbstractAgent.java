package agents;

import llm.LLMClient;
import model.Draft;
import model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Base class of all the agents that use the LLM (cognitive agents).
 *
 * TEMPLATE METHOD pattern: handle() fixes the same 4 steps for every agent,
 *   1. buildContext()  -> DIFFERENT for each agent (its own knowledge)   [abstract]
 *   2. build the message (email + history + feedback)  -> same for all
 *   3. call the local LLM                                -> same for all
 *   4. checkReply()    -> optional final check, by default it does nothing  [hook]
 * A subclass only fills the steps that make it special.
 *
 * The two dimensions of specialization asked by the assignment (section 5):
 *   - the SYSTEM PROMPT comes from system_prompts.md (via PromptRepository);
 *   - the CONTEXT is built by buildContext() from the agent's own files/data.
 */
public abstract class AbstractAgent implements Agent {

    private final String name;
    private final String description;
    private final List<String> keywords;
    private final PromptRepository prompts;
    protected final LLMClient llm;

    protected AbstractAgent(String name, String description, List<String> keywords,
                            PromptRepository prompts, LLMClient llm) {
        this.name = name;
        this.description = description;
        this.keywords = keywords;
        this.prompts = prompts;
        this.llm = llm;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public List<String> getKeywords() { return keywords; }

    /** The template method: final, so no subclass can change the order of the steps. */
    @Override
    public final Draft handle(Email email, String history, String feedback) {
        Draft draft = new Draft(name);

        String context = buildContext(email, draft);                               // step 1
        String systemPrompt = prompts.get(name) + "\n\n### CONTEXT\n" + context;
        String userMessage = buildUserMessage(email, history, feedback);           // step 2
        String reply = llm.generate(systemPrompt, userMessage);                    // step 3
        draft.setText(checkReply(reply.trim(), email, draft));                     // step 4

        return draft;
    }

    /** Step 1: the knowledge this agent gives to the LLM. It can also add notes to the draft. */
    protected abstract String buildContext(Email email, Draft draft);

    /** Step 4 (hook): a last check on the LLM answer. By default it keeps the answer as it is. */
    protected String checkReply(String reply, Email email, Draft draft) {
        return reply;
    }

    private String buildUserMessage(Email email, String history, String feedback) {
        StringBuilder sb = new StringBuilder();
        if (!history.isBlank()) {
            sb.append("PREVIOUS EXCHANGES WITH THIS SENDER (oldest first):\n")
              .append(history).append("\n");
        }
        sb.append("NEW EMAIL TO ANSWER\n")
          .append("From: ").append(email.getSender()).append("\n")
          .append("Subject: ").append(email.getSubject()).append("\n")
          .append("Body:\n").append(email.getBody()).append("\n");
        if (!feedback.isBlank()) {
            sb.append("\n").append(feedback).append("\n");
        }
        sb.append("\nWrite only the body of the reply email, in English.");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Small helpers shared by the agents
    // ------------------------------------------------------------------

    /** Reads a context file. It is read at every email, so edits are used immediately. */
    protected static String readFile(String path) {
        try {
            return Files.readString(Path.of(path)).replace("\r", "");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the context file " + path, e);
        }
    }

    /**
     * Reads a markdown table: returns its rows (without the header), each row as an array of cells.
     * @param afterLine if not null, the table is searched only after the line containing this text
     */
    protected static List<String[]> readTable(String markdown, String afterLine) {
        List<String[]> rows = new ArrayList<>();
        boolean searching = (afterLine != null);
        boolean headerSkipped = false;
        boolean inTable = false;

        for (String line : markdown.split("\n")) {
            String t = line.trim();
            if (searching) {
                if (t.contains(afterLine)) searching = false;
                continue;
            }
            if (t.startsWith("|")) {
                inTable = true;
                if (!headerSkipped) { headerSkipped = true; continue; }                  // header row
                if (t.replace("|", "").replace("-", "").replace(":", "").isBlank()) continue; // |---|---|
                String inside = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length());
                String[] cells = inside.split("\\|");
                for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
                rows.add(cells);
            } else if (inTable) {
                break;                                                                  // table finished
            }
        }
        return rows;
    }

    /** Subject + body in lower case, ready for simple text checks. */
    protected static String textOf(Email email) {
        return (email.getSubject() + "\n" + email.getBody()).toLowerCase().replace('\u2019', '\'');
    }

    protected static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** "dr.ferris@stcharleshospital.org" -> "stcharleshospital.org" */
    protected static String domainOf(String emailAddress) {
        int at = emailAddress.indexOf('@');
        return at < 0 ? "" : emailAddress.substring(at + 1).trim().toLowerCase();
    }

    protected static String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
    }

    protected static String formatSlot(LocalDateTime slot) {
        return slot.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm", Locale.ENGLISH));
    }
}
