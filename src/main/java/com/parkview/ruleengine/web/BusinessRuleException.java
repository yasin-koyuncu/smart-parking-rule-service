package com.parkview.ruleengine.web;

/** The request is well-formed but violates a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException implements Localizable {

    private final String code;
    private final transient Object[] args;

    public BusinessRuleException(String message) {
        this(null, message, new Object[0]);
    }

    private BusinessRuleException(String code, String message, Object[] args) {
        super(message);
        this.code = code;
        this.args = args;
    }

    /** With a stable {@code code} and the English {@code message}, which stays the text when no translation exists. */
    public static BusinessRuleException coded(String code, String message, Object... args) {
        return new BusinessRuleException(code, message, args);
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
