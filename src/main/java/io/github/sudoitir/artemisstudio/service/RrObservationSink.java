package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.domain.rr.Observation;

/** Where {@link io.github.sudoitir.artemisstudio.broker.core.RrSampler} and the notification observer hand facts. {@link RrCorrelator} is the real implementation. */
public interface RrObservationSink {

    void accept(Observation observation);
}
