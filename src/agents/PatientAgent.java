package agents;

import calendar.AppointmentCalendar;
import llm.LLMClient;
import model.Appointment;
import model.DoctorSchedule;
import model.Draft;
import model.Email;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replies to PATIENTS: bookings, cancellations, opening hours, payments, documents,
 * and patients describing symptoms (without ever giving medical advice).
 *
 * Its context = the practice FAQ (full file: it is short, RAG is not needed)
 *             + FACTS COMPUTED IN JAVA from the calendar (bookings, free slots).
 * The LLM never "reads" the calendar itself: a 3B model is not reliable with dates,
 * so Java computes the availability and the LLM only turns it into a nice sentence.
 */
public class PatientAgent extends AbstractAgent {

    private static final String DESCRIPTION =
            "Emails from patients: booking, cancelling or moving appointments, opening hours, payments, "
            + "documents to bring, and patients describing symptoms or asking medical questions.";

    private static final List<String> KEYWORDS = List.of(
            "appointment", "book", "cancel", "reschedul", "postpone", "visit", "opening hours",
            "pay", "invoice", "by card", "cash", "bring", "pain", "symptom", "headache", "dizz",
            "nause", "medication", "dosage", "worried", "call me back");

    /** Words showing that the patient wants a (new) slot. Only then free slots are proposed. */
    private static final String[] BOOKING_WORDS = {
            "book a", "book an", "to book", "like to book", "reschedul", "postpone", "move my",
            "availab", "slot", "cancel", "new appointment"};

    private static final String[] SYMPTOM_WORDS = {
            "pain", "symptom", "headache", "dizz", "nause", "fever", "hurt", "worried",
            "medication", "dosage", "side effect", "rash"};

    private final String contextFile;
    private final AppointmentCalendar calendar;

    public PatientAgent(String contextFile, AppointmentCalendar calendar, PromptRepository prompts, LLMClient llm) {
        super("PatientAgent", DESCRIPTION, KEYWORDS, prompts, llm);
        this.contextFile = contextFile;
        this.calendar = calendar;
    }

    @Override
    protected String buildContext(Email email, Draft draft) {
        String text = textOf(email);
        StringBuilder c = new StringBuilder();

        c.append(readFile(contextFile)).append("\n\n");
        c.append("## CALENDAR DATA (computed by the system from the practice calendar, reliable)\n");
        c.append("Today is ").append(formatDate(AppointmentCalendar.TODAY)).append(".\n");

        // 1. Existing bookings of this sender (useful for cancellations and moves)
        List<Appointment> bookings = calendar.findBookingsByEmail(email.getSender());
        if (bookings.isEmpty()) {
            c.append("No booking is registered in the calendar for the sender's email address. "
                    + "If they talk about an existing appointment, ask them to confirm date, time and doctor.\n");
        } else {
            for (Appointment a : bookings) {
                c.append("Booking registered for this sender: ").append(a.getDoctorName()).append(", ")
                 .append(formatSlot(LocalDateTime.of(a.getDate(), a.getTime()))).append(".\n");
            }
            draft.addNote("Calendar: found " + bookings.size() + " booking(s) of this sender.");
        }

        // 2. Free slots, only if the patient wants to book or move a visit
        //    (the doctor named in the email, otherwise general medicine)
        if (containsAny(text, BOOKING_WORDS)) {
            Optional<DoctorSchedule> mentioned = calendar.findDoctorMentionedIn(text);
            DoctorSchedule doctor = mentioned.orElse(
                    calendar.findDoctorBySpecialization("General medicine").orElse(null));
            if (doctor != null) {
                LocalDate from = requestedStartDate(text);
                List<LocalDateTime> slots = chooseSlots(calendar.availableSlots(doctor.getDoctorName(), from, 30), text);
                c.append("Next free slots of ").append(doctor.getDoctorName())
                 .append(" (").append(doctor.getSpecialization()).append("):\n");
                for (LocalDateTime slot : slots) {
                    c.append("- ").append(formatSlot(slot)).append("\n");
                }
                if (slots.isEmpty()) c.append("- no free slot in the next 30 days\n");
                draft.addNote("Calendar: proposed " + slots.size() + " free slot(s) of "
                        + doctor.getDoctorName() + " from " + from + ".");
            }
        }

        // 3. Clinical content: a reminder for the LLM and a note for the reviewer
        if (containsAny(text, SYMPTOM_WORDS)) {
            c.append("\nATTENTION: the email describes symptoms or asks for medical advice. "
                    + "Do not interpret them and do not suggest any treatment.\n");
            draft.addNote("Clinical content detected: the reply must not contain medical advice.");
        }
        return c.toString();
    }

    /**
     * "tomorrow", "next week", "Friday"... -> the first day to look at in the calendar.
     * If the patient wants to MOVE away from a day ("move my appointment from Monday"),
     * the search starts the day after, so we do not propose the day they want to leave.
     */
    private LocalDate requestedStartDate(String text) {
        LocalDate today = AppointmentCalendar.TODAY;
        boolean movingAway = containsAny(text, "move", "postpone", "reschedul", "cancel");
        String[] dayNames = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
        for (int i = 0; i < dayNames.length; i++) {
            if (text.contains(dayNames[i])) {
                LocalDate day = today.with(TemporalAdjusters.next(DayOfWeek.of(i + 1)));
                return movingAway ? day.plusDays(1) : day;
            }
        }
        if (text.contains("tomorrow")) return today.plusDays(1);
        if (text.contains("next week")) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return today;
    }

    /** Keeps 3 slots, respecting "morning" / "afternoon" if the patient asked for it. */
    private List<LocalDateTime> chooseSlots(List<LocalDateTime> all, String text) {
        List<LocalDateTime> chosen = new ArrayList<>();
        String t = text.replace("good morning", "").replace("good afternoon", "").replace("good evening", "");
        boolean morning = t.contains("morning");
        boolean afternoon = containsAny(t, "afternoon", "evening", "late ");
        int fromHour = containsAny(t, "late afternoon", "evening") ? 15 : 14;
        for (LocalDateTime slot : all) {
            boolean ok = (!morning && !afternoon)
                    || (morning && slot.getHour() < 12)
                    || (afternoon && slot.getHour() >= fromHour);
            if (ok) chosen.add(slot);
            if (chosen.size() == 3) break;
        }
        if (chosen.isEmpty()) {                       // preference impossible: give the first ones
            chosen.addAll(all.subList(0, Math.min(3, all.size())));
        }
        return chosen;
    }
}
