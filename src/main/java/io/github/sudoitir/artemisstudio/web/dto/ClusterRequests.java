package io.github.sudoitir.artemisstudio.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The write side of the cluster API. */
public final class ClusterRequests {

    private ClusterRequests() {}

    /**
     * {@code POST /clusters}. A cluster is registered from a <em>list</em> of seed
     * URLs (ADR-0013): paste the management addresses you can actually reach, and
     * discovery matches them to broker NodeIDs.
     *
     * @param seedUrls full Jolokia base URLs, e.g. {@code http://broker-1:8161/console/jolokia}
     * @param name optional display name; defaults to the first seed's host
     * @param credentials optional HTTP Basic credentials, shared by every node
     * @param tlsBundle optional Spring SSL bundle name for HTTPS brokers
     */
    public record RegisterClusterRequest(
            @NotEmpty List<@NotBlank String> seedUrls,
            String name,
            String description,
            @Valid Credentials credentials,
            String tlsBundle) {

        public record Credentials(
                @NotBlank String username, @NotBlank String password) {}

        public boolean hasCredentials() {
            return credentials != null;
        }
    }

    /** {@code PATCH /clusters/{id}/nodes/{nodeId}} — give a discovered node a reachable management URL. */
    public record NodeOverrideRequest(@NotBlank String jolokiaUrl) {}

    /**
     * {@code PUT /clusters/{id}/credentials} — rotate the stored HTTP Basic
     * credentials for every node of a cluster. The new secret is AES-GCM sealed
     * (ADR-0009) and the change is audited in the same transaction; the response
     * carries no secret. Typed cluster-name confirmation is enforced in the UI.
     */
    public record RotateCredentialsRequest(
            @NotBlank String username, @NotBlank String password) {}
}
