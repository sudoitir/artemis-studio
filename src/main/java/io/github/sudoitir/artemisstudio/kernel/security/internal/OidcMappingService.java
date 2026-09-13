package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.web.OidcMappingViews.OidcMappingRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.OidcMappingViews.OidcMappingView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for {@code oidc_role_mapping} (oidc-sso spec). Every write needs {@code user:admin}. */
@Service
@RequiredArgsConstructor
public class OidcMappingService {

    private final OidcRoleMappingRepository mappings;
    private final RoleRepository roles;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<OidcMappingView> list() {
        return mappings.findAllByOrderByClaimAscClaimValueAsc().stream()
                .map(this::toView)
                .toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public OidcMappingView create(OidcMappingRequest request) {
        roles.findById(request.roleId()).orElseThrow(() -> new NotFoundException("role", request.roleId()));
        UUID scopeId = request.scopeId() != null ? request.scopeId() : ScopeIds.GLOBAL;
        OidcRoleMappingEntity saved = mappings.save(new OidcRoleMappingEntity(
                request.claim(), request.claimValue(), request.roleId(), request.scopeType(), scopeId));
        return toView(saved);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void delete(UUID mappingId) {
        if (!mappings.existsById(mappingId)) {
            throw new NotFoundException("oidc mapping", mappingId);
        }
        mappings.deleteById(mappingId);
    }

    private OidcMappingView toView(OidcRoleMappingEntity m) {
        String roleName = roles.findById(m.getRoleId())
                .map(io.github.sudoitir.artemisstudio.kernel.security.internal.RoleEntity::getName)
                .orElse("?");
        return new OidcMappingView(
                m.getId(), m.getClaim(), m.getClaimValue(), m.getRoleId(), roleName, m.getScopeType(), m.getScopeId());
    }
}
