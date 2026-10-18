package com.procureflow.app.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    /** Minimum documented fields; RFC 9457 type/instance fields may also be returned. */
    public record ErrorBody(int status, String title, String code) {}
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException exception) {
        return problem(exception.status(), exception.code());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation() { return problem(HttpStatus.BAD_REQUEST, "validation_failed"); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    ProblemDetail malformed() { return problem(HttpStatus.BAD_REQUEST, "invalid_request"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() { return problem(HttpStatus.CONFLICT, "data_conflict"); }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class, QueryTimeoutException.class})
    ProblemDetail concurrency() { return problem(HttpStatus.CONFLICT, "retryable_conflict"); }

    @ExceptionHandler(UncategorizedSQLException.class)
    ProblemDetail database(UncategorizedSQLException exception) {
        // Spring's generic SQL-state translator does not classify PostgreSQL 55P03 (lock_timeout).
        var state = exception.getSQLException().getSQLState();
        boolean retryable = "55P03".equals(state) || "40P01".equals(state) || "40001".equals(state) || "57014".equals(state);
        return retryable ? problem(HttpStatus.CONFLICT, "retryable_conflict") : problem(HttpStatus.INTERNAL_SERVER_ERROR, "database_error");
    }

    private ProblemDetail problem(HttpStatus status, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
