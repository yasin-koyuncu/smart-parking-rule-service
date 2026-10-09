package com.parkview.ruleengine.web;

/** The request conflicts with current state, e.g. duplicate or already-processed (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
