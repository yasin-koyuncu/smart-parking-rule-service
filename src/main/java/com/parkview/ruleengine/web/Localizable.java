package com.parkview.ruleengine.web;

/**
 * An error whose text can be shown in the language of the caller. {@code code} is stable (an API: clients may switch on it),
 * for example {@code session.voided}; the text is looked up in {@code messages/errors[_lang].properties} with {@code args}
 * ({@code {0}}, {@code {1}}...). Without a code, or without an entry for it, the exception's own (English) message is
 * the text, so a service can convert its messages one by one.
 */
public interface Localizable {

    /** Stable machine-readable code, or {@code null} when the exception has none. */
    String code();

    /** Parameters of the message, never {@code null}. */
    Object[] args();
}
