package agents;

import calendar.AppointmentCalendar;
import llm.LLMClient;
import model.Draft;
import model.Email;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles LABORATORY emails: test status, turnaround times, blood draws,
 * reports sent by external labs.
 *
 * Privacy by design, on two levels:
 *  1. BEFORE the LLM: its context never contains clinical values. The records file has
 *     only metadata (test type, date, status), and the status of a test is given only if
 *     the sender is the patient's registered address.
 *  2. AFTER the LLM (checkReply hook of the Template Method): a deterministic guard removes
 *     any clinical value or patient name that the LLM copied from the incoming email
 *     (e.g. a lab writes "Hb 10.8 g/dL" and a small model repeats it in the draft).
 *     This plays the role of a simple "critic" that checks the answer before the human sees it.
 */
public class LabAgent extends AbstractAgent {

    private static final String DESCRIPTION =
            "Emails about laboratory tests: when results are ready, how reports are delivered, "
            + "blood-draw bookings, fasting rules, and reports sent by external laboratories.";

    private static final List<String> KEYWORDS = List.of(
            "blood test", "blood draw", "test results", "results", "lab", "laboratory",
            "fasting", "sample", "report", "turnaround");

    /** A number followed by a medical unit, e.g. "10.8 g/dL", "180 mg/dL", "5.4 mmol/L". */
    private static final Pattern CLINICAL_VALUE = Pattern.compile(
            "\\b\\d+(?:[.,]\\d+)?\\s*(?:g/dl|mg/dl|mmol/l|mg/l|ng/ml|u/l|iu/l|mmhg)",
            Pattern.CASE_INSENSITIVE);

    /** A date like 14/03/1978 (e.g. a date of birth copied from the lab's email). */
    private static final Pattern DATE = Pattern.compile("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b");

    /** A few diagnosis words that must never appear in an automatic draft. */
    private static final Pattern DIAGNOSIS = Pattern.compile(
            "\\b(anemia|anaemia|anemic|anaemic|diabetes|tumou?r|cancer|infection)\\b",
            Pattern.CASE_INSENSITIVE);

    private final String contextFile;
    private final String recordsFile;

    public LabAgent(String contextFile, String recordsFile, PromptRepository prompts, LLMClient llm) {
        super("LabAgent", DESCRIPTION, KEYWORDS, prompts, llm);
        this.contextFile = contextFile;
        this.recordsFile = recordsFile;
    }

    @Override
    protected String buildContext(Email email, Draft draft) {
        StringBuilder c = new StringBuilder(readFile(contextFile));
        c.append("\n\nToday is ").append(formatDate(AppointmentCalendar.TODAY)).append(".\n");
        c.append("\n## RECORD CHECK (computed by the system, final)\n");

        List<String[]> records = readTable(readFile(recordsFile), null);  // name, email, test, date, status
        String[] own = null;
        String[] mentioned = null;
        String text = textOf(email);
        for (String[] row : records) {
            if (row.length < 5) continue;
            if (row[1].equalsIgnoreCase(email.getSender().trim())) own = row;
            else if (text.contains(row[0].toLowerCase())) mentioned = row;
        }

        if (own != null) {
            String status = own[4].equalsIgnoreCase("ready")
                    ? "READY for collection (in person with an ID, or on the patient portal)"
                    : "PENDING (the sample has not been processed yet)";
            c.append("A record exists for this sender: test \"").append(own[2]).append("\", requested on ")
             .append(own[3]).append(", status: ").append(status).append(".\n");
            draft.addNote("Lab record found for the sender: " + own[2] + " (" + own[4] + ").");
        } else {
            c.append("No lab record is linked to the sender's email address. Do not confirm or deny that any "
                    + "test exists for any person; give only general information.\n");
            if (mentioned != null) {
                draft.addNote("The email mentions a patient who has lab records, but the sender is not that "
                        + "patient: no status is disclosed.");
            }
        }
        return c.toString();
    }

    /** Privacy guard: runs on the LLM answer, before the human sees it. */
    @Override
    protected String checkReply(String reply, Email email, Draft draft) {
        int removed = 0;

        Matcher values = CLINICAL_VALUE.matcher(reply);
        while (values.find()) removed++;
        String cleaned = CLINICAL_VALUE.matcher(reply).replaceAll("[value removed]");

        Matcher dates = DATE.matcher(cleaned);
        while (dates.find()) removed++;
        cleaned = DATE.matcher(cleaned).replaceAll("[date removed]");

        Matcher diagnoses = DIAGNOSIS.matcher(cleaned);
        while (diagnoses.find()) removed++;
        cleaned = DIAGNOSIS.matcher(cleaned).replaceAll("[clinical detail removed]");

        for (String patientName : namesToHide(email)) {
            Pattern name = Pattern.compile("(?:\\bthe\\s+)?(?:\\bpatient\\s+)?" + Pattern.quote(patientName),
                    Pattern.CASE_INSENSITIVE);
            Matcher m = name.matcher(cleaned);
            while (m.find()) removed++;
            cleaned = name.matcher(cleaned).replaceAll("the patient");
        }

        if (removed > 0) {
            draft.addWarning("Privacy guard removed " + removed + " clinical value(s), date(s), diagnosis word(s) "
                    + "or patient name(s) that the LLM had written in the draft.");
        } else {
            draft.addNote("Privacy guard: no clinical value or patient name found in the draft.");
        }
        return cleaned;
    }

    /**
     * Names that must not appear in the draft: the patients of the records file
     * (except the sender, who can be greeted by name) and any "patient Name Surname"
     * written in the incoming email.
     */
    private List<String> namesToHide(Email email) {
        List<String> names = new ArrayList<>();
        for (String[] row : readTable(readFile(recordsFile), null)) {
            if (row.length >= 5 && !row[1].equalsIgnoreCase(email.getSender().trim())) names.add(row[0]);
        }
        Matcher m = Pattern.compile("[Pp]atient ([A-Z][a-z]+ [A-Z][a-z]+)").matcher(email.getBody());
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
