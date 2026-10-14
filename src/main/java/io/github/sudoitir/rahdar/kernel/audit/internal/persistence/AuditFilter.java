package io.github.sudoitir.rahdar.kernel.audit.internal.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * What an audit read keeps (see {@link AuditEventRepository#FILTER}). String and id filters are optional
 * ({@code null} drops the predicate); {@code from} and {@code to} are always set (widened to sentinels when
 * unset) because Postgres cannot infer the type of a timestamp parameter used only in an {@code is null} test.
 */
public record AuditFilter(
        UUID clusterId,
        boolean everything,
        Collection<String> queues,
        Collection<String> addresses,
        String username,
        String action,
        String outcome,
        Long parentId,
        Instant from,
        Instant to) {}
