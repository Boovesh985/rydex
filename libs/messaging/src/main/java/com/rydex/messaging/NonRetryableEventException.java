package com.rydex.messaging;

/** An event that can never be processed (malformed, missing fields): skip retries and dead-letter it. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }
}
