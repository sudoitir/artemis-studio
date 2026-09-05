package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.OidcMappingService;
import io.github.sudoitir.artemisstudio.web.dto.OidcMappingViews.OidcMappingRequest;
import io.github.sudoitir.artemisstudio.web.dto.OidcMappingViews.OidcMappingView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** OIDC claim → role mapping CRUD (oidc-sso spec). */
@RestController
@RequestMapping("/api/v1/oidc/mappings")
@RequiredArgsConstructor
public class OidcMappingController {

    private final OidcMappingService mappings;

    @GetMapping
    public List<OidcMappingView> list() {
        return mappings.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OidcMappingView create(@Valid @RequestBody OidcMappingRequest request) {
        return mappings.create(request);
    }

    @DeleteMapping("/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID mappingId) {
        mappings.delete(mappingId);
    }
}
