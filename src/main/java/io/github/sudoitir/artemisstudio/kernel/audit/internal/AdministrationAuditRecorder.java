package io.github.sudoitir.artemisstudio.kernel.audit.internal;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AdministrationAuditRecorder implements AdministrationAudit {

    private final AuditService audit;
    private final ActorResolver actors;

    @Override
    public void changed(String action, String targetType, String targetName, Map<String, ?> params) {
        audit.succeed(audit.begin(actors.resolve(), action, targetType, targetName, null, null, params, false), 1);
    }
}
