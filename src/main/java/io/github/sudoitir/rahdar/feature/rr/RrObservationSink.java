package io.github.sudoitir.rahdar.feature.rr;

/** Where {@link RrSampler} and the notification observer hand facts. {@link RrCorrelator} is the real implementation. */
public interface RrObservationSink {

    void accept(Observation observation);
}
