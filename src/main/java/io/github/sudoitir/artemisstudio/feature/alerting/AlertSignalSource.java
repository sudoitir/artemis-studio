package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.UUID;

/**
 * A cluster state another module evaluates for a built-in state rule. The module that owns the
 * state implements this, so alerting reads no other module's persistence; while that module is
 * disabled, the rule has nothing to evaluate.
 */
public interface AlertSignalSource {

    /** The state condition this source answers, as stored on the rule, e.g. {@code CONFIG_DRIFT}. */
    String condition();

    AlertCondition.Evaluation evaluate(UUID clusterId);
}
