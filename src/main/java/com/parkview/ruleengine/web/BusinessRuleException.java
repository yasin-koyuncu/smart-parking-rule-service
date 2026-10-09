package com.parkview.ruleengine.web;

/** The request is well-formed but violates a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
