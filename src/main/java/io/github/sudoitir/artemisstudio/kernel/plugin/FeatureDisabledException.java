package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A request addressed something a disabled feature owns (feature-modules spec).
 * Always answered as {@code 404} with problem type {@code feature-disabled}, naming
 * the property that enables the feature.
 */
public class FeatureDisabledException extends RuntimeException {

    private final transient FeatureDescriptor feature;

    public FeatureDisabledException(FeatureDescriptor feature) {
        super(feature.title() + " is disabled on this installation. Set " + Contract.enabledProperty(feature.id())
                + "=true to enable it.");
        this.feature = feature;
    }

    public FeatureDescriptor feature() {
        return feature;
    }

    public ProblemDetail toProblem() {
        ProblemDetail problem = Problems.of(HttpStatus.NOT_FOUND, "feature-disabled", "Feature disabled", getMessage());
        problem.setProperty("featureId", feature.id());
        problem.setProperty("property", Contract.enabledProperty(feature.id()));
        return problem;
    }
}
