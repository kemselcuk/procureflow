package com.procureflow.app.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException exception) {
        return problem(exception.status(), exception.code());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation() { return problem(HttpStatus.BAD_REQUEST, "validation_failed"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() { return problem(HttpStatus.CONFLICT, "data_conflict"); }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    ProblemDetail concurrency() { return problem(HttpStatus.CONFLICT, "retryable_conflict"); }

    private ProblemDetail problem(HttpStatus status, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
