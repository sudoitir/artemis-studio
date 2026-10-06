package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.PatternKind;
import io.github.sudoitir.artemisstudio.kernel.security.internal.TeamService;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRoleRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternPreview;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ShareView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamSummary;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.UnownedView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teams, their patterns, members and shares (team-access spec). Patterns, shares and the teams
 * themselves need {@code user:admin}; a team's members can also be managed by a holder of
 * {@code team:admin} in that team.
 */
@RestController
@RequiredArgsConstructor
public class TeamsController {

    private final TeamService teams;

    @GetMapping("/teams")
    public PagedView<TeamSummary> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(teams.list(), null);
    }

    @GetMapping("/teams/{teamId}")
    public TeamView get(@PathVariable UUID teamId) {
        return teams.get(teamId);
    }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamView create(@Valid @RequestBody TeamRequest request) {
        return teams.create(request.name());
    }

    @PutMapping("/teams/{teamId}")
    public TeamView rename(@PathVariable UUID teamId, @Valid @RequestBody TeamRequest request) {
        return teams.rename(teamId, request.name());
    }

    @DeleteMapping("/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID teamId) {
        teams.delete(teamId);
    }

    @PostMapping("/teams/{teamId}/patterns")
    @ResponseStatus(HttpStatus.CREATED)
    public PatternView addPattern(@PathVariable UUID teamId, @Valid @RequestBody PatternRequest request) {
        return teams.addPattern(teamId, request);
    }

    @DeleteMapping("/teams/{teamId}/patterns/{patternId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePattern(@PathVariable UUID teamId, @PathVariable UUID patternId) {
        teams.removePattern(teamId, patternId);
    }

    @GetMapping("/teams/{teamId}/patterns/preview")
    public PatternPreview preview(
            @PathVariable UUID teamId,
            @RequestParam UUID clusterId,
            @RequestParam PatternKind kind,
            @RequestParam String pattern) {
        return teams.preview(teamId, clusterId, kind, pattern);
    }

    @PostMapping("/teams/{teamId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@PathVariable UUID teamId, @Valid @RequestBody MemberRequest request) {
        return teams.addMember(teamId, request);
    }

    @PutMapping("/teams/{teamId}/members/{memberId}")
    public MemberView changeMemberRole(
            @PathVariable UUID teamId, @PathVariable UUID memberId, @Valid @RequestBody MemberRoleRequest request) {
        return teams.changeMemberRole(teamId, memberId, request.roleId());
    }

    @DeleteMapping("/teams/{teamId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID memberId) {
        teams.removeMember(teamId, memberId);
    }

    @PostMapping("/teams/{teamId}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareView addShare(@PathVariable UUID teamId, @Valid @RequestBody ShareRequest request) {
        return teams.addShare(teamId, request);
    }

    @DeleteMapping("/teams/{teamId}/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeShare(@PathVariable UUID teamId, @PathVariable UUID shareId) {
        teams.removeShare(teamId, shareId);
    }

    /** The queue or address names of a cluster that no team owns. */
    @GetMapping("/clusters/{clusterId}/unowned")
    public PagedView<UnownedView> unowned(
            @PathVariable UUID clusterId,
            @RequestParam ResourceKind kind,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        teams.unowned(clusterId, kind).stream()
                                .map(UnownedView::new)
                                .toList(),
                        null);
    }
}
