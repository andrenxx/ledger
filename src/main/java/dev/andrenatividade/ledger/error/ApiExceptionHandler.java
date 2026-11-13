package dev.andrenatividade.ledger.error;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://andrenatividade.is-a.dev/ledger/errors/";

    @ExceptionHandler(LedgerException.class)
    ProblemDetail handleLedger(LedgerException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + ex.getCode()));
        problem.setTitle(ex.getCode());
        ex.getDetails().forEach(problem::setProperty);
        return problem;
    }

    // ex.: dois estornos do mesmo lançamento ao mesmo tempo -> unique em reverses_id
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("integrity violation", ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A operação conflita com outra feita ao mesmo tempo. Tente de novo.");
        problem.setType(URI.create(TYPE_BASE + "conflict"));
        problem.setTitle("conflict");
        return problem;
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ProblemDetail handleConstraint(jakarta.validation.ConstraintViolationException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "invalid-request"));
        problem.setTitle("invalid-request");
        return problem;
    }
}
