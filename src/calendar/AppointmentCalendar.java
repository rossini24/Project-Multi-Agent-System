package calendar;

import model.Appointment;
import model.DoctorSchedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AppointmentCalendar {

    public static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private final Map<String, DoctorSchedule> schedules = new HashMap<>();
    private final List<Appointment> appointments = new ArrayList<>();

    public AppointmentCalendar(String schedulePath, String appointmentsPath) throws IOException {
        loadSchedules(schedulePath);
        loadAppointments(appointmentsPath);
    }

    private void loadSchedules(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",");
            Set<DayOfWeek> days = Arrays.stream(f[2].split("\\|"))
                    .map(this::parseDay)
                    .collect(Collectors.toSet());
            schedules.put(f[0], new DoctorSchedule(
                    f[0], f[1], days, LocalTime.parse(f[3]), LocalTime.parse(f[4]), Integer.parseInt(f[5].trim())));
        }
    }

    private void loadAppointments(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",");
            appointments.add(new Appointment(
                    Integer.parseInt(f[0]), f[1], LocalDate.parse(f[2]), LocalTime.parse(f[3]),
                    f[4], f[5], f[6].trim()));
        }
    }

    private DayOfWeek parseDay(String code) {
        return switch (code.trim()) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown day in doctors_schedule.csv: " + code);
        };
    }

    public boolean isAvailable(String doctorName, LocalDate date, LocalTime time) {
        DoctorSchedule schedule = schedules.get(doctorName);
        if (schedule == null || !schedule.worksOn(date.getDayOfWeek())) return false;
        if (time.isBefore(schedule.getStartTime()) || !time.isBefore(schedule.getEndTime())) return false;

        return appointments.stream().noneMatch(a ->
                a.getDoctorName().equals(doctorName)
                && a.getDate().equals(date)
                && a.getTime().equals(time)
                && a.getStatus().equals("booked"));
    }

    public Optional<LocalDateTime> nextAvailableSlot(String doctorName, LocalDate fromDate) {
        DoctorSchedule schedule = schedules.get(doctorName);
        if (schedule == null) return Optional.empty();

        for (LocalDate date = fromDate; date.isBefore(fromDate.plusDays(30)); date = date.plusDays(1)) {
            if (!schedule.worksOn(date.getDayOfWeek())) continue;
            for (LocalTime time = schedule.getStartTime();
                 time.isBefore(schedule.getEndTime());
                 time = time.plusMinutes(schedule.getSlotMinutes())) {
                if (isAvailable(doctorName, date, time)) {
                    return Optional.of(LocalDateTime.of(date, time));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Appointment> findBookedAppointment(String patientEmail, String doctorName) {
        return appointments.stream()
                .filter(a -> a.getPatientEmail().equalsIgnoreCase(patientEmail)
                        && a.getDoctorName().equals(doctorName)
                        && a.getStatus().equals("booked"))
                .findFirst();
    }

    // =====================================================================
    // Query methods ADDED for the agents (the methods above are unchanged).
    // The calendar is a shared resource of the environment: PatientAgent and
    // DoctorAgent both read it, but only through these methods.
    // =====================================================================

    /** All doctors of the practice, sorted by name. */
    public List<DoctorSchedule> getDoctors() {
        List<DoctorSchedule> list = new ArrayList<>(schedules.values());
        list.sort(Comparator.comparing(DoctorSchedule::getDoctorName));
        return list;
    }

    /** The first 'max' free slots of a doctor, starting from 'fromDate' (looks 30 days ahead). */
    public List<LocalDateTime> availableSlots(String doctorName, LocalDate fromDate, int max) {
        List<LocalDateTime> result = new ArrayList<>();
        DoctorSchedule schedule = schedules.get(doctorName);
        if (schedule == null) return result;

        for (LocalDate date = fromDate; date.isBefore(fromDate.plusDays(30)); date = date.plusDays(1)) {
            if (!schedule.worksOn(date.getDayOfWeek())) continue;
            for (LocalTime time = schedule.getStartTime();
                 time.isBefore(schedule.getEndTime());
                 time = time.plusMinutes(schedule.getSlotMinutes())) {
                if (isAvailable(doctorName, date, time)) {
                    result.add(LocalDateTime.of(date, time));
                    if (result.size() >= max) return result;
                }
            }
        }
        return result;
    }

    /** All booked appointments of a patient, whatever the doctor. */
    public List<Appointment> findBookingsByEmail(String patientEmail) {
        return appointments.stream()
                .filter(a -> a.getPatientEmail().equalsIgnoreCase(patientEmail)
                        && a.getStatus().equals("booked"))
                .toList();
    }

    /** Words that point to a specialization (first word of each row = specialization name in the CSV). */
    private static final String[][] SPECIALIZATION_WORDS = {
            {"Cardiology", "cardiolog", "cardiac", "heart", "arrhythmia"},
            {"Endocrinology", "endocrin", "diabet", "thyroid", "hormon"},
            {"Dermatology", "dermatolog", "skin", "mole", "rash", "eczema"},
            {"General medicine", "general medicine", "general practitioner", "family doctor"}
    };

    /** Finds the doctor an email talks about: first by surname, then by specialization words. */
    public Optional<DoctorSchedule> findDoctorMentionedIn(String text) {
        String t = text.toLowerCase();
        for (DoctorSchedule d : getDoctors()) {
            String[] parts = d.getDoctorName().toLowerCase().split(" ");
            String surname = parts[parts.length - 1];
            if (Pattern.compile("\\b" + surname + "\\b").matcher(t).find()) return Optional.of(d);
        }
        for (String[] row : SPECIALIZATION_WORDS) {
            for (int i = 1; i < row.length; i++) {
                if (t.contains(row[i])) return findDoctorBySpecialization(row[0]);
            }
        }
        return Optional.empty();
    }

    public Optional<DoctorSchedule> findDoctorBySpecialization(String specialization) {
        return getDoctors().stream()
                .filter(d -> d.getSpecialization().equalsIgnoreCase(specialization))
                .findFirst();
    }
}
