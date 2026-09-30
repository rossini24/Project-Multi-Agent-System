package model;

import java.time.LocalDate;
import java.time.LocalTime;

public class Appointment {
    private final int id;
    private final String doctorName;
    private final LocalDate date;
    private final LocalTime time;
    private final String patientName;
    private final String patientEmail;
    private final String status;

    public Appointment(int id, String doctorName, LocalDate date, LocalTime time,
                        String patientName, String patientEmail, String status) {
        this.id = id;
        this.doctorName = doctorName;
        this.date = date;
        this.time = time;
        this.patientName = patientName;
        this.patientEmail = patientEmail;
        this.status = status;
    }

    public int getId() { return id; }
    public String getDoctorName() { return doctorName; }
    public LocalDate getDate() { return date; }
    public LocalTime getTime() { return time; }
    public String getPatientName() { return patientName; }
    public String getPatientEmail() { return patientEmail; }
    public String getStatus() { return status; }
}
