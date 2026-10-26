package com.parkview.ruleengine.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.List;

/**
 * One error format for every endpoint: RFC 7807 {@code application/problem+json}.
 * Extra members: {@code timestamp}, {@code requestId}, {@code message} (copy of {@code detail},
 * kept because the frontend reads {@code message}) and {@code errors} for validation failures.
 * Internal details of unexpected failures are logged, never returned.
 *
 * <p>The texts are in the language of the caller ({@code Accept-Language}): an exception that is {@link Localizable} with a code is
 * looked up in {@code messages/errors[_lang].properties} (see {@link ErrorMessagesConfig}), everything else keeps its English
 * message. The response also carries the stable {@code code} when there is one, so clients can switch on it. Logs stay English.
 */
@RestControllerAdvice
@Import(ErrorMessagesConfig.class)   // the message source comes with the handler, also in a web slice that scans no configuration
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MessageSource messages;

    public ApiExceptionHandler(@Qualifier("errorMessages") MessageSource messages) {
        this.messages = messages;
    }

    public record FieldViolation(String field, String message) {
    }

    /** The text of {@code code} in the caller's language, or {@code fallback} (English) when there is no entry. */
    private String text(String code, String fallback, Object... args) {
        return messages.getMessage(code, args, fallback, LocaleContextHolder.getLocale());
    }

    private ProblemDetail localized(HttpStatus status, String titleKey, String title, RuntimeException ex) {
        String code = ex instanceof Localizable l ? l.code() : null;
        Object[] args = ex instanceof Localizable l ? l.args() : new Object[0];
        // no default message here: the exception's own text is returned as it is, not run through MessageFormat
        String translated = code == null ? null : messages.getMessage(code, args, null, LocaleContextHolder.getLocale());
        String detail = translated != null ? translated : ex.getMessage();
        ProblemDetail pd = problem(status, text(titleKey, title), detail);
        if (code != null) {
            pd.setProperty("code", code);
        }
        return pd;
    }

    private ProblemDetail fixed(HttpStatus status, String key, String title, String detail) {
        ProblemDetail pd = problem(status, text("title." + key, title), text("error." + key, detail));
        pd.setProperty("code", "error." + key);
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ex) {
        return localized(HttpStatus.NOT_FOUND, "title.notFound", "Resource not found", ex);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex) {
        return localized(HttpStatus.CONFLICT, "title.conflict", "Conflict", ex);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex) {
        return localized(HttpStatus.UNPROCESSABLE_ENTITY, "title.businessRule", "Business rule violated", ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return fixed(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You are not allowed to perform this action");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ProblemDetail dataConflict(Exception ex) {
        log.warn("Data conflict: {}", ex.getMessage());
        return fixed(HttpStatus.CONFLICT, "dataConflict", "Conflict",
                "The request conflicts with the current state of the resource");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = fixed(HttpStatus.BAD_REQUEST, "validation.params", "Validation failed", "Request parameters are invalid");
        pd.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage())).toList());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled exception (requestId={})", MDC.get(CorrelationIdFilter.MDC_KEY), ex);
        return fixed(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldViolation(f.getField(), f.getDefaultMessage())).toList();
        ProblemDetail pd = fixed(HttpStatus.BAD_REQUEST, "validation.body", "Validation failed", "Request body is invalid");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new FieldViolation(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        ProblemDetail pd = fixed(HttpStatus.BAD_REQUEST, "validation.params", "Validation failed", "Request parameters are invalid");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail pd = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(statusCode);
        decorate(pd);
        return super.handleExceptionInternal(ex, pd, headers, statusCode, request);
    }

    public static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        decorate(pd);
        return pd;
    }

    static void decorate(ProblemDetail pd) {
        pd.setProperty("timestamp", Instant.now().toString());
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        if (pd.getDetail() != null) {
            pd.setProperty("message", pd.getDetail());
        }
    }
}
