package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.security.internal.MyAccess;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.AccessSummary;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the signed-in caller holds (authorization spec). Any signed-in caller may ask about themselves. */
@RestController
@RequiredArgsConstructor
public class AccessController {

    private final MyAccess access;

    /** The caller's permissions globally, or on one cluster, and their teams. */
    @GetMapping("/me/access")
    public AccessSummary mine(@RequestParam(required = false) UUID clusterId) {
        return access.of(clusterId);
    }
}
