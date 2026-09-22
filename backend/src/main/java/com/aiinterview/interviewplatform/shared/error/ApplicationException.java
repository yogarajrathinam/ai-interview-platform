package com.aiinterview.interviewplatform.shared.error;

/**
 * Base class for every error the application raises deliberately.
 *
 * <p>Business code throws these; controllers contain no {@code try/catch}.
 * {@link GlobalExceptionHandler} is the single place that turns them into
 * HTTP responses.
 *
 * <p>{@code detail} is written for a human reader and is safe to display: it
 * must never contain SQL, stack traces, provider messages, or identifiers
 * belonging to another user.
 */
public class ApplicationException extends RuntimeException {

    private final ErrorCode code;

    public ApplicationException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ApplicationException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    // ---------------------------------------------------------- factories

    /**
     * Use for a resource that does not exist <em>and</em> for one the caller
     * may not see. Returning 403 for the latter would confirm it exists.
     */
    public static ApplicationException notFound(String detail) {
        return new ApplicationException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApplicationException conflict(ErrorCode code, String detail) {
        return new ApplicationException(code, detail);
    }

    public static ApplicationException forbidden(String detail) {
        return new ApplicationException(ErrorCode.FORBIDDEN, detail);
    }

    public static ApplicationException validation(String detail) {
        return new ApplicationException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
