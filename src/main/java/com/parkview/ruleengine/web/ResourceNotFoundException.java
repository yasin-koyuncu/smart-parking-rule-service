package com.parkview.ruleengine.web;

import java.util.Locale;

/** The requested resource does not exist (HTTP 404). Code {@code notFound.<resource>} (lower case, spaces as '-'). */
public class ResourceNotFoundException extends RuntimeException implements Localizable {

    private final String code;
    private final transient Object[] args;

    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
        this.code = "notFound." + resource.toLowerCase(Locale.ROOT).replace(' ', '-');
        this.args = new Object[] {String.valueOf(id)};
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
