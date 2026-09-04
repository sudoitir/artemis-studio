package io.github.sudoitir.artemisstudio.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
     * @param coreCredentials optional Core-protocol credentials; when omitted, the
     *     Core client reuses {@code credentials} (ADR-0026)
     * @param tlsBundle optional Spring SSL bundle name for HTTPS brokers
     */
    public record RegisterClusterRequest(
            @NotEmpty List<@NotBlank String> seedUrls,
            String name,
            String description,
            @Valid Credentials credentials,
            @Valid Credentials coreCredentials,
            String tlsBundle) {

        public record Credentials(
                @NotBlank String username, @NotBlank String password) {}

        public boolean hasCredentials() {
            return credentials != null;
        }

        public boolean hasCoreCredentials() {
            return coreCredentials != null;
        }
    }

    /**
     * {@code PATCH /clusters/{id}/nodes/{nodeId}} — give a discovered node a
     * reachable management URL, a reachable Core URL, or both. At least one is
     * required (ADR-0026); a manual value is never overwritten by rediscovery.
     */
    public record NodeOverrideRequest(String jolokiaUrl, String coreUrl) {

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "at least one of jolokiaUrl or coreUrl is required") public boolean isAtLeastOneUrlPresent() {
            return isPresent(jolokiaUrl) || isPresent(coreUrl);
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasJolokiaUrl() {
            return isPresent(jolokiaUrl);
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean hasCoreUrl() {
            return isPresent(coreUrl);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * {@code PUT /clusters/{id}/credentials} — rotate a stored credential for
     * every node of a cluster. The new secret is AES-GCM sealed (ADR-0009) and
     * the change is audited in the same transaction; the response carries no
     * secret. Typed cluster-name confirmation is enforced in the UI.
     *
     * @param kind {@code JOLOKIA_BASIC} (default) or {@code CORE}
     */
    public record RotateCredentialsRequest(
            @NotBlank String username, @NotBlank String password, String kind) {

        @JsonIgnore
        @Schema(hidden = true)
        public String kindOrDefault() {
            return kind == null || kind.isBlank() ? "JOLOKIA_BASIC" : kind;
        }
    }
}
