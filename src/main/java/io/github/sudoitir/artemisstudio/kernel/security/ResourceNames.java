package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.List;
import java.util.UUID;

/**
 * The queue and address names Studio currently knows on a cluster, for previewing what a team pattern
 * covers and for listing what no team owns. Implemented by the module that scrapes the brokers, so the
 * security kernel needs no broker access.
 */
public interface ResourceNames {

    /** Distinct queue names of the cluster, sorted. */
    List<String> queues(UUID clusterId);

    /** Distinct address names of the cluster, sorted. */
    List<String> addresses(UUID clusterId);
}
