package io.github.sudoitir.artemisstudio.platform.clusters.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.sudoitir.artemisstudio.platform.clusters.ManagementUrlPattern;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** The write side of the cluster API. */
public final class ClusterRequests {

    private ClusterRequests() {}

    /**
     * {@code POST /clusters}. A cluster is registered from a <em>list</em> of seed
     * URLs (ADR-0013): paste the management addresses you can actually reach, and
     * discovery matches them to broker NodeIDs. One seed is enough: the rest of the
     * cluster's nodes get a management URL derived from {@code managementUrlPattern}
     * and proved by NodeID (ADR-0175).
     *
     * @param seedUrls full Jolokia base URLs, e.g. {@code http://broker-1:8161/console/jolokia}
     * @param name optional display name; defaults to the first seed's host
     * @param credentials optional HTTP Basic credentials, shared by every node
     * @param coreCredentials optional Core-protocol credentials; when omitted, the
     *     Core client reuses {@code credentials} (ADR-0026)
     * @param tlsBundle optional Spring SSL bundle name for HTTPS brokers
     * @param managementUrlPattern optional {@code scheme://{host}:port/path}; defaults to the first seed with
     *     its host replaced by {@code {host}}
     * @param environmentId optional environment to place the cluster in
     * @param adopt whether to save what the cluster's live nodes run as its revision 1 (ADR-0176)
     */
    public record RegisterClusterRequest(
            @NotEmpty List<@NotBlank String> seedUrls,
            String name,
            String description,
            @Valid Credentials credentials,
            @Valid Credentials coreCredentials,
            String tlsBundle,
            String managementUrlPattern,
            UUID environmentId,
            Boolean adopt) {

        public record Credentials(
                @NotBlank String username, @NotBlank String password) {}

        public boolean hasCredentials() {
            return credentials != null;
        }

        public boolean hasCoreCredentials() {
            return coreCredentials != null;
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "Put the account in the Management account fields, not in the URL.") public boolean isSeedsWithoutAccount() {
            return seedUrls == null || seedUrls.stream().noneMatch(ManagementUrlPattern::hasUserInfo);
        }

        @JsonIgnore
        @Schema(hidden = true)
        public boolean adopts() {
            return Boolean.TRUE.equals(adopt);
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(
                message = "managementUrlPattern must be http(s)://{host}[:port][/path], with {host} as the whole host")
        public boolean isPatternValid() {
            return managementUrlPattern == null
                    || managementUrlPattern.isBlank()
                    || ManagementUrlPattern.isValid(managementUrlPattern);
        }
    }

    /**
     * One account in {@link UpdateClusterRequest}: the password is optional, because an empty one keeps the
     * stored one.
     */
    public record AccountUpdate(String username, String password) {

        /** What a JSON {@code null} for the Core account reads as: clear it, so Core uses the management account. */
        public static final AccountUpdate CLEAR = new AccountUpdate(null, null);

        boolean named() {
            return username != null && !username.isBlank();
        }
    }

    /** Tells a Core account that is {@code null} in the request from one that is left out: that one is {@code null} in Java. */
    static final class CoreAccountDeserializer extends ValueDeserializer<AccountUpdate> {

        @Override
        public AccountUpdate deserialize(JsonParser parser, DeserializationContext context) {
            return context.readValue(parser, AccountUpdate.class);
        }

        @Override
        public AccountUpdate getNullValue(DeserializationContext context) {
            return AccountUpdate.CLEAR;
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /**
     * {@code PATCH /clusters/{id}}: change any of a cluster's connection, in one request. A field left out is
     * left as it is, and an empty password keeps the stored one. {@code core} set to {@code null} clears the
     * Core account, so Core connections use the management account again. {@code ?dryRun=true} checks the
     * edited connection node by node and saves nothing. Typed confirmation of a credential change is
     * enforced in the UI.
     *
     * @param seedUrls the management URLs the operator gives as seeds; a seed no longer listed stops being one
     * @param tlsBundle a Spring SSL bundle name; empty clears it
     */
    public record UpdateClusterRequest(
            String name,
            String description,
            List<@NotBlank String> seedUrls,
            String managementUrlPattern,
            String tlsBundle,
            AccountUpdate management,

            @JsonDeserialize(using = CoreAccountDeserializer.class)
            AccountUpdate core) {

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "an account needs a username") public boolean isAccountNamed() {
            return (management == null || management.named())
                    && (core == null || core == AccountUpdate.CLEAR || core.named());
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(
                message = "managementUrlPattern must be http(s)://{host}[:port][/path], with {host} as the whole host")
        public boolean isPatternValid() {
            return managementUrlPattern == null || ManagementUrlPattern.isValid(managementUrlPattern);
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "Put the account in the Management account fields, not in the URL.") public boolean isSeedsWithoutAccount() {
            return seedUrls == null || seedUrls.stream().noneMatch(ManagementUrlPattern::hasUserInfo);
        }

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "name must not be blank") public boolean isNameValid() {
            return name == null || !name.isBlank();
        }
    }

    /**
     * {@code PATCH /clusters/{id}/nodes/{nodeId}} — give a discovered node a
     * reachable management URL, a reachable Core URL, or both. At least one is
     * required (ADR-0026); a manual value is never overwritten by discovery.
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
}
