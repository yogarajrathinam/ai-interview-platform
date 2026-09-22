package com.aiinterview.interviewplatform.shared.error;

import com.aiinterview.interviewplatform.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place HTTP error responses are produced.
 *
 * <p>Every non-2xx response is an RFC 9457 {@code application/problem+json}
 * document carrying a stable {@code code} and the request's {@code traceId}.
 * Stack traces, SQL and provider messages are never exposed: unexpected
 * failures are logged in full and reported as a bare {@code INTERNAL_ERROR}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplication(ApplicationException ex,
                                                           HttpServletRequest request) {
        // Deliberate, expected outcomes: recorded at INFO/WARN, never ERROR.
        log.info("Request rejected: code={} detail={}", ex.code(), ex.getMessage());
        return ResponseEntity
                .status(ex.code().status())
                .body(problem(ex.code(), ex.getMessage(), request.getRequestURI(), null));
    }

    /** Anything not deliberately raised. The detail is deliberately generic. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex,
                                                          HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.status())
                .body(problem(ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred. Quote the traceId when reporting it.",
                        request.getRequestURI(), null));
    }

    // ------------------------------------------------- Spring MVC failures

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(
                        fe.getField(),
                        fe.getCode() == null ? "INVALID" : toCode(fe.getCode()),
                        fe.getDefaultMessage()))
                .toList();

        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.", path(request), violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Syntactically broken body -> 400. Semantically invalid data -> 422.
        return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status())
                .body(problem(ErrorCode.MALFORMED_REQUEST,
                        "Request body could not be parsed.", path(request), null));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(problem(ErrorCode.MALFORMED_REQUEST,
                        "HTTP method not supported for this resource.", path(request), null));
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(problem(ErrorCode.NOT_FOUND, "No such endpoint.", path(request), null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(problem(ErrorCode.VALIDATION_FAILED,
                        "Parameter '%s' has an invalid value.".formatted(ex.getName()),
                        request.getRequestURI(),
                        List.of(new FieldViolation(ex.getName(), "TYPE_MISMATCH",
                                "Value is not of the expected type."))));
    }

    // ------------------------------------------------------------ helpers

    private ProblemDetail problem(ErrorCode code, String detail, String instance,
                                  List<FieldViolation> violations) {
        ProblemDetail pd = ProblemDetail.forStatus(code.status());
        pd.setType(URI.create(code.type()));
        pd.setTitle(code.title());
        pd.setDetail(detail);
        if (instance != null) {
            pd.setInstance(URI.create(instance));
        }
        pd.setProperty("code", code.name());
        pd.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        if (violations != null && !violations.isEmpty()) {
            pd.setProperty("errors", violations);
        }
        return pd;
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }

    /** {@code NotBlank} -> {@code NOT_BLANK}. */
    private String toCode(String constraintName) {
        return constraintName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }
}
