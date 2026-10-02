package io.github.sudoitir.artemisstudio.platform.clusters.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterAlreadyRegisteredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Cluster registration refusals, as problem details. */
@RestControllerAdvice
class ClusterProblemAdvice {

    /**
     * The brokers are already registered (ADR-0167). 409: the request is well-formed, a registered cluster
     * is what refuses it. The cluster's id and name ride along, so the form can link to it; both are
     * absent when the caller may not see that cluster.
     */
    @ExceptionHandler(ClusterAlreadyRegisteredException.class)
    ProblemDetail onAlreadyRegistered(ClusterAlreadyRegisteredException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.CONFLICT,
                "cluster-already-registered",
                "These brokers are already registered",
                e.getMessage());
        if (e.existingClusterId() != null) {
            problem.setProperty("existingClusterId", e.existingClusterId());
            problem.setProperty("existingClusterName", e.existingClusterName());
            problem.setProperty("overlappingNodes", e.overlappingNodes());
        }
        return problem;
    }
}
