package io.github.sudoitir.artemisstudio.feature.alerting;

/**
 * A state of Studio itself that an installation-scoped rule (ADR-0135) reads, the way an
 * {@link AlertSignalSource} answers a cluster's. Evaluated once per installation, never per
 * cluster.
 */
public interface InstallationSignalSource {

    /** The state condition this source answers, as stored on the rule, e.g. {@code STORAGE_QUOTA}. */
    String condition();

    AlertCondition.Evaluation evaluate();
}
