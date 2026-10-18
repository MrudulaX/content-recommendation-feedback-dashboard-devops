package com.crfd.dashboard.service;

/** Thrown when a requested aggregate (event, alert, feedback) does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException event(String eventId) {
        return new NotFoundException("No event found with eventId '" + eventId + "'");
    }

    public static NotFoundException alert(String alertRef) {
        return new NotFoundException("No alert found with alertRef '" + alertRef + "'");
    }
}
