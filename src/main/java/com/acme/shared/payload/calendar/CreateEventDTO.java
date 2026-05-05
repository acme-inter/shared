package com.acme.shared.payload.calendar;

import lombok.Data;

import java.util.List;

@Data
public class CreateEventDTO {
  private String summary;
  private String description;
  private String location;
  private String startDateTime;
  private String endDateTime;
  private String timeZone;
  private boolean allDay;
  private String startDate;
  private String endDate;
  private List<String> attendees;
  private Integer reminderMinutes;
  private List<String> recurrence;
  private String conferenceSolution;
  private String visibility;
  private String transparency;
  private String status;
}
