package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adopting a cluster's running configuration as its first declared revision, as a step of registering
 * it (ADR-0176). The configuration feature implements it, so the clusters module never names the
 * feature; when the feature is off there is no implementation, and registration offers no adoption.
 */
public interface RegistrationAdoption {

    /** A live node the check reached, with the connection to read it through. */
    record LiveNode(String name, JolokiaBrokerClient client) {}

    /** How many entries of each section adoption would declare. */
    record Counts(int addresses, int addressSettings, int securitySettings, int diverts) {}

    /**
     * What adopting would declare.
     *
     * @param disagreements items the nodes report differently, each naming the nodes and their values
     */
    record Preview(Counts counts, List<String> disagreements, List<String> notes) {}

    /**
     * What adopting the running configuration of these nodes would declare. Reads the nodes and writes
     * nothing. Empty when the caller may not declare configuration, or no node could be read.
     */
    Optional<Preview> preview(List<LiveNode> nodes);

    /**
     * Saves what the cluster's live nodes run as its revision 1, attributed to the caller and audited, in
     * the caller's transaction. The cluster and its nodes are already saved in it.
     */
    void adopt(UUID clusterId);
}
