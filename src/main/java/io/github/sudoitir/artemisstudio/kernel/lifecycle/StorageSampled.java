package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.time.Instant;

/** Published after each storage sample, so installation alerts can be evaluated on fresh numbers. */
public record StorageSampled(Instant at) {}
