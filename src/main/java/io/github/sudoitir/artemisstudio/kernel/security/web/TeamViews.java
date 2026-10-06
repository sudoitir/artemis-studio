package io.github.sudoitir.artemisstudio.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.security.PatternKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Teams, the patterns they own, their members and their shares (team-access spec). */
public final class TeamViews {

    private TeamViews() {}

    public record TeamRequest(@NotBlank String name) {}

    public record PatternRequest(
            @NotNull UUID clusterId,
            @NotNull PatternKind kind,
            @NotBlank String pattern) {}

    public enum PrincipalType {
        USER,
        GROUP
    }

    @Schema(description = "A user (userId), or a directory group (providerId and groupName).")
    public record MemberRequest(
            @NotNull PrincipalType principalType,
            @Schema(nullable = true) UUID userId,
            @Schema(nullable = true) String providerId,
            @Schema(nullable = true) String groupName,
            @NotNull UUID roleId) {}

    public record MemberRoleRequest(@NotNull UUID roleId) {}

    public record ShareRequest(
            @NotNull UUID targetTeamId,
            @NotNull UUID clusterId,
            @NotNull PatternKind kind,
            @NotBlank String pattern,
            @NotNull UUID roleId) {}

    public record TeamSummary(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) int memberCount,

            @Schema(requiredMode = REQUIRED, description = "The patterns the team owns, with the cluster of each.")
            List<PatternView> patterns,

            @Schema(requiredMode = REQUIRED) int sharesOut,
            @Schema(requiredMode = REQUIRED) int sharesIn) {}

    public record PatternView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) PatternKind kind,
            @Schema(requiredMode = REQUIRED) String pattern) {}

    public record MemberView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) PrincipalType principalType,
            @Schema(nullable = true) UUID userId,
            @Schema(nullable = true) String username,
            @Schema(nullable = true) String providerId,
            @Schema(nullable = true) String groupName,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String roleName) {}

    @Schema(
            description = "covered is false when the owner's patterns no longer contain the shared pattern; "
                    + "the share then grants nothing until they do again.")
    public record ShareView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID ownerTeamId,
            @Schema(requiredMode = REQUIRED) String ownerTeamName,
            @Schema(requiredMode = REQUIRED) UUID targetTeamId,
            @Schema(requiredMode = REQUIRED) String targetTeamName,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) PatternKind kind,
            @Schema(requiredMode = REQUIRED) String pattern,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String roleName,
            @Schema(requiredMode = REQUIRED) boolean covered) {}

    public record TeamView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) List<PatternView> patterns,
            @Schema(requiredMode = REQUIRED) List<MemberView> members,
            @Schema(requiredMode = REQUIRED) List<ShareView> sharesOut,
            @Schema(requiredMode = REQUIRED) List<ShareView> sharesIn) {}

    /** Another team's pattern that some name could match together with the one being added. */
    public record PatternConflict(
            @Schema(requiredMode = REQUIRED) UUID teamId,
            @Schema(requiredMode = REQUIRED) String teamName,
            @Schema(requiredMode = REQUIRED) PatternKind kind,
            @Schema(requiredMode = REQUIRED) String pattern) {}

    public record PatternPreview(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) PatternKind kind,
            @Schema(requiredMode = REQUIRED) String pattern,

            @Schema(requiredMode = REQUIRED, description = "Queues on the cluster the pattern matches.")
            int queueMatches,

            @Schema(requiredMode = REQUIRED, description = "Addresses on the cluster the pattern matches.")
            int addressMatches,

            @Schema(requiredMode = REQUIRED, description = "Up to 20 matching queue names.")
            List<String> queueExamples,

            @Schema(requiredMode = REQUIRED, description = "Up to 20 matching address names.")
            List<String> addressExamples,

            @Schema(requiredMode = REQUIRED) List<PatternConflict> conflicts) {}

    public record UnownedView(
            @Schema(requiredMode = REQUIRED) String name) {}

    @Schema(
            description =
                    "A user a team admin may add as a member: enabled accounts only. Usernames are unique across providers.")
    public record UserLookup(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String username) {}

    @Schema(description = "The team roles a team admin may give, and the catalogue entries their permissions name.")
    public record TeamRoleLookup(
            @Schema(requiredMode = REQUIRED) List<UserViews.RoleView> roles,
            @Schema(requiredMode = REQUIRED) List<UserViews.PermissionView> permissions) {}
}
