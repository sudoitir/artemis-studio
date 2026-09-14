package io.github.sudoitir.artemisstudio.feature.sql.web;

import io.github.sudoitir.artemisstudio.feature.sql.CostRefusedException;
import io.github.sudoitir.artemisstudio.feature.sql.SqlConsoleService;
import io.github.sudoitir.artemisstudio.feature.sql.SqlSyntaxException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SQL console refusals as problem details. Public because the stream controller
 * reports the same refusals inside an event stream rather than as a response.
 */
@RestControllerAdvice
public class SqlProblemAdvice {

    /**
     * A query that is not in the dialect (ADR-0058 D2). The offending token and the
     * near match travel as properties so the editor can put the caret on the word.
     */
    @ExceptionHandler(SqlSyntaxException.class)
    public ProblemDetail onSqlSyntax(SqlSyntaxException e) {
        ProblemDetail problem =
                Problems.of(HttpStatus.BAD_REQUEST, "sql-syntax", "That is not the console's dialect", e.getMessage());
        if (e.offending() != null) {
            problem.setProperty("offending", e.offending());
        }
        if (e.suggestion() != null) {
            problem.setProperty("suggestion", e.suggestion());
        }
        return problem;
    }

    /**
     * A query refused before its first broker call (ADR-0058 D6). It carries the
     * estimate, the ceiling and the way to narrow it, because "too expensive" with no
     * number is not something an operator can act on.
     */
    @ExceptionHandler(CostRefusedException.class)
    public ProblemDetail onCostRefused(CostRefusedException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "query-too-expensive",
                "Query refused before it was started",
                e.getMessage());
        problem.setProperty("estimate", e.estimate());
        problem.setProperty("ceiling", e.ceiling());
        problem.setProperty("hint", e.hint());
        return problem;
    }

    @ExceptionHandler(SqlConsoleService.TooManyQueriesException.class)
    public ProblemDetail onTooManyQueries(SqlConsoleService.TooManyQueriesException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.TOO_MANY_REQUESTS, "too-many-queries", "Too many queries at once", e.getMessage());
        problem.setProperty("cap", e.cap());
        return problem;
    }
}
