package model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

public class DoctorSchedule {
    private final String doctorName;
    private final String specialization;
    private final Set<DayOfWeek> workingDays;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final int slotMinutes;

    public DoctorSchedule(String doctorName, String specialization, Set<DayOfWeek> workingDays,
                           LocalTime startTime, LocalTime endTime, int slotMinutes) {
        this.doctorName = doctorName;
        this.specialization = specialization;
        this.workingDays = workingDays;
        this.startTime = startTime;
        this.endTime = endTime;
        this.slotMinutes = slotMinutes;
    }

    public String getDoctorName() { return doctorName; }
    public String getSpecialization() { return specialization; }
    public boolean worksOn(DayOfWeek day) { return workingDays.contains(day); }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public int getSlotMinutes() { return slotMinutes; }
}
