package dev.schemaforge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) { return problem(e.status, e.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity() { return problem(409, "Database constraint violation"); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> bad(Exception e) { return problem(400, "Invalid request value"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(DataAccessException e) {
        // Do not log SQL, payloads, credentials, or driver exception messages.
        log.error("Database operation failed: {}", e.getClass().getSimpleName());
        return problem(503, "Database operation unavailable");
    }
    private ResponseEntity<ProblemDetail> problem(int status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
    }
}
