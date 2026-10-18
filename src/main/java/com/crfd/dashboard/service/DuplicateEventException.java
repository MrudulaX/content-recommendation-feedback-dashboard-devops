package com.crfd.dashboard.service;

/** Raised when an event with the same business key has already been ingested. */
public class DuplicateEventException extends RuntimeException {

    private final String eventId;

    public DuplicateEventException(String eventId) {
        super("Event '" + eventId + "' has already been ingested");
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
