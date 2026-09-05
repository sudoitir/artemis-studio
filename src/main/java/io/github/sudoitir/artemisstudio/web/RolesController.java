package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.RoleService;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.PermissionView;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.RoleView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Custom role CRUD and the permission catalogue (authorization spec). Every write needs {@code user:admin}. */
@RestController
@RequiredArgsConstructor
public class RolesController {

    private final RoleService roleService;

    @GetMapping("/api/v1/roles")
    public List<RoleView> list() {
        return roleService.list();
    }

    @PostMapping("/api/v1/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView create(@Valid @RequestBody RoleRequest request) {
        return roleService.create(request);
    }

    @PutMapping("/api/v1/roles/{roleId}")
    public RoleView update(@PathVariable UUID roleId, @Valid @RequestBody RoleRequest request) {
        return roleService.update(roleId, request);
    }

    @DeleteMapping("/api/v1/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID roleId) {
        roleService.delete(roleId);
    }

    @GetMapping("/api/v1/permissions")
    public List<PermissionView> permissions() {
        return roleService.catalogue();
    }
}
