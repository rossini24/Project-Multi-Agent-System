package agents;

import calendar.AppointmentCalendar;
import llm.LLMClient;
import model.DoctorSchedule;
import model.Draft;
import model.Email;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Replies to external DOCTORS (referrals, consultations, requests for documents).
 *
 * MANIPULATION IN MAS (course, part 1 - reputation): a reputation or trust mechanism
 * breaks when an agent can fake its IDENTITY (Sybil attack, impersonation).
 * The defence seen in class is a "certified message origin / stronger identity check".
 * Here: the identity check is done IN JAVA, deterministically, before the LLM is called.
 * A small LLM can be convinced by an urgent, professional-sounding email; a string
 * comparison on the email domain cannot. The LLM only receives the RESULT of the check.
 *
 * Verified = the sender's domain is the OFFICIAL domain of a listed doctor
 *            AND that doctor's name appears in the email (address or text).
 */
public class DoctorAgent extends AbstractAgent {

    private static final String DESCRIPTION =
            "Emails from external doctors acting as professionals: referrals, consultations, "
            + "patient handovers, requests for a patient's clinical documents or reports.";

    private static final List<String> KEYWORDS = List.of(
            "referral", "refer", "consultation", "handover", "second opinion", "patient file",
            "clinical documentation", "clinical history", "shared patient", "my patient",
            "treatment", "specialist", "colleague");

    private final String contextFile;
    private final AppointmentCalendar calendar;

    public DoctorAgent(String contextFile, AppointmentCalendar calendar, PromptRepository prompts, LLMClient llm) {
        super("DoctorAgent", DESCRIPTION, KEYWORDS, prompts, llm);
        this.contextFile = contextFile;
        this.calendar = calendar;
    }

    @Override
    protected String buildContext(Email email, Draft draft) {
        String contextText = readFile(contextFile);
        StringBuilder c = new StringBuilder(contextText);

        c.append("\n\n## IDENTITY CHECK (computed by the system, final)\n")
         .append(checkIdentity(email, contextText, draft)).append("\n");

        c.append("\n## PRACTICE CALENDAR (reliable)\n")
         .append("Today is ").append(formatDate(AppointmentCalendar.TODAY)).append(".\n")
         .append("Weekly schedule of the doctors:\n");
        for (DoctorSchedule d : calendar.getDoctors()) {
            c.append("- ").append(d.getDoctorName()).append(" (").append(d.getSpecialization()).append("): ")
             .append(workingDays(d)).append(", ").append(d.getStartTime()).append("-").append(d.getEndTime())
             .append("\n");
        }

        Optional<DoctorSchedule> requested = calendar.findDoctorMentionedIn(textOf(email));
        if (requested.isPresent()) {
            DoctorSchedule d = requested.get();
            c.append("First free slots of ").append(d.getDoctorName()).append(" (")
             .append(d.getSpecialization()).append("):\n");
            List<LocalDateTime> slots = calendar.availableSlots(d.getDoctorName(), AppointmentCalendar.TODAY, 3);
            for (LocalDateTime slot : slots) {
                c.append("- ").append(formatSlot(slot)).append("\n");
            }
            draft.addNote("Calendar: first free slots of " + d.getDoctorName() + " added to the context.");
        }
        return c.toString();
    }

    /** The heart of this agent: who is really writing? */
    private String checkIdentity(Email email, String contextText, Draft draft) {
        List<String[]> verified = readTable(contextText, "Verified external doctors");
        String senderDomain = domainOf(email.getSender());
        String everything = (email.getSender() + " " + email.getSubject() + " " + email.getBody()).toLowerCase();

        String[] claimed = null;   // a verified doctor whose name is used, but from the wrong domain
        for (String[] row : verified) {
            if (row.length < 3) continue;
            String doctor = row[0];
            String affiliation = row[1];
            String officialDomain = row[2].toLowerCase();
            boolean nameUsed = mentions(everything, surnameOf(doctor));

            if (nameUsed && senderDomain.equals(officialDomain)) {
                draft.addNote("Identity verified: " + doctor + " (" + affiliation + "), official domain @" + officialDomain + ".");
                return "VERIFIED. The sender is " + doctor + " from " + affiliation
                        + ", writing from the official domain @" + officialDomain
                        + ". The request can be processed following the documentation-sharing procedure.";
            }
            if (nameUsed) claimed = row;
        }

        if (claimed != null) {
            draft.addWarning("Possible impersonation: the email uses the name of the verified doctor "
                    + claimed[0] + " (" + claimed[1] + "), but it comes from @" + senderDomain
                    + " instead of the official @" + claimed[2] + ". No patient information must be shared.");
            return "NOT VERIFIED. The sender claims to be " + claimed[0] + " but writes from @" + senderDomain
                    + ", which is NOT the official domain @" + claimed[2]
                    + ". Share NO patient information and ask them to write again from their official institutional address.";
        }

        if (containsAny(everything, "patient", "report", "file", "record")) {
            draft.addWarning("Unverified sender (@" + senderDomain + ") asking about patients: "
                    + "not in the verified doctors directory. No patient information must be shared.");
        } else {
            draft.addNote("Sender not in the verified doctors directory (general question only).");
        }
        return "NOT VERIFIED. The sender is not in the practice's verified doctors directory. "
                + "Share NO patient information; general questions (specialists, availability) can be answered.";
    }

    /** "Dr. Ferris" -> "ferris" */
    private static String surnameOf(String doctor) {
        String[] parts = doctor.trim().split(" ");
        return parts[parts.length - 1].toLowerCase();
    }

    /** Whole-word search, so that "lane" does not match "plane". */
    private static boolean mentions(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static String workingDays(DoctorSchedule d) {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (d.worksOn(day)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            }
        }
        return sb.toString();
    }
}
