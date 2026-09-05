package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.UserService;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.CreateUserRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.SetDisabledRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.UserView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User CRUD and role grants (authorization spec). Every write needs {@code user:admin}. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UsersController {

    private final UserService users;

    @GetMapping
    public List<UserView> list() {
        return users.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request);
    }

    @PutMapping("/{userId}/disabled")
    public UserView setDisabled(@PathVariable UUID userId, @RequestBody SetDisabledRequest request) {
        return users.setDisabled(userId, request.disabled());
    }

    @PostMapping("/{userId}/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addGrant(@PathVariable UUID userId, @Valid @RequestBody GrantRequest request) {
        users.addGrant(userId, request);
    }

    @DeleteMapping("/{userId}/grants/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGrant(
            @PathVariable UUID userId,
            @PathVariable UUID roleId,
            @RequestParam String scopeType,
            @RequestParam(required = false) UUID scopeId) {
        users.removeGrant(userId, roleId, scopeType, scopeId);
    }
}
