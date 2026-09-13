package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.security.internal.GroupMappingService;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.DefaultRoleRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingView;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingsView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Group -> role mappings and the default role, per external identity provider (ADR-0073). */
@RestController
@RequestMapping("/api/v1/identity/providers/{providerId}/group-mappings")
@RequiredArgsConstructor
public class GroupMappingController {

    private final GroupMappingService mappings;

    @GetMapping
    public GroupMappingsView list(@PathVariable String providerId) {
        return mappings.list(providerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupMappingView create(@PathVariable String providerId, @Valid @RequestBody GroupMappingRequest request) {
        return mappings.create(providerId, request);
    }

    @DeleteMapping("/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String providerId, @PathVariable UUID mappingId) {
        mappings.delete(providerId, mappingId);
    }

    @PutMapping("/default-role")
    public GroupMappingsView setDefaultRole(@PathVariable String providerId, @RequestBody DefaultRoleRequest request) {
        return mappings.setDefaultRole(providerId, request.roleId());
    }
}
