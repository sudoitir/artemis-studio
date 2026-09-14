package io.github.sudoitir.artemisstudio.feature.rr;

/** Where {@link io.github.sudoitir.artemisstudio.feature.rr.RrSampler} and the notification observer hand facts. {@link RrCorrelator} is the real implementation. */
public interface RrObservationSink {

    void accept(Observation observation);
}
