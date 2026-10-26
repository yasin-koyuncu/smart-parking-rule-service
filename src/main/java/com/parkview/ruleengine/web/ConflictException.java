package com.parkview.ruleengine.web;

/** The request conflicts with current state, e.g. duplicate or already-processed (HTTP 409). */
public class ConflictException extends RuntimeException implements Localizable {

    private final String code;
    private final transient Object[] args;

    public ConflictException(String message) {
        this(null, message, new Object[0]);
    }

    private ConflictException(String code, String message, Object[] args) {
        super(message);
        this.code = code;
        this.args = args;
    }

    /** With a stable {@code code} and the English {@code message}, which stays the text when no translation exists. */
    public static ConflictException coded(String code, String message, Object... args) {
        return new ConflictException(code, message, args);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Object[] args() {
        return args;
    }
}
